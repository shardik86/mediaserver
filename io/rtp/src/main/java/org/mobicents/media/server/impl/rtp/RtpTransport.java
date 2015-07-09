/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2014, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 */

package org.mobicents.media.server.impl.rtp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.DatagramChannel;

import org.apache.log4j.Logger;
import org.mobicents.media.io.ice.IceAuthenticator;
import org.mobicents.media.io.ice.network.stun.StunHandler;
import org.mobicents.media.server.component.audio.AudioComponent;
import org.mobicents.media.server.component.oob.OOBComponent;
import org.mobicents.media.server.impl.rtcp.RtcpHandler;
import org.mobicents.media.server.impl.rtp.sdp.RTPFormats;
import org.mobicents.media.server.impl.rtp.statistics.RtpStatistics;
import org.mobicents.media.server.impl.srtp.DtlsHandler;
import org.mobicents.media.server.impl.srtp.DtlsListener;
import org.mobicents.media.server.io.network.UdpManager;
import org.mobicents.media.server.io.network.channel.MultiplexedChannel;
import org.mobicents.media.server.scheduler.Scheduler;
import org.mobicents.media.server.scheduler.Task;
import org.mobicents.media.server.spi.ConnectionMode;
import org.mobicents.media.server.spi.FormatNotSupportedException;
import org.mobicents.media.server.spi.dsp.Processor;
import org.mobicents.media.server.spi.format.AudioFormat;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.spi.format.Formats;
import org.mobicents.media.server.utils.Text;

/**
 * 
 * @author Yulian Oifa
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 *
 */
public class RtpTransport extends MultiplexedChannel implements DtlsListener {

    private static final Logger logger = Logger.getLogger(RtpTransport.class);

    protected final static AudioFormat LINEAR_FORMAT = FormatFactory.createAudioFormat("LINEAR", 8000, 16, 1);
    protected final static AudioFormat DTMF_FORMAT = FormatFactory.createAudioFormat("telephone-event", 8000);
    static {
        DTMF_FORMAT.setOptions(new Text("0-15"));
    }
    
    private final static int PORT_ANY = -1;
    
    // Core elements
    private final UdpManager udpManager;
    private final Scheduler scheduler;
    private final HeartBeat heartBeat;

    // Channel attributes
    private final int channelId;
    private SocketAddress remotePeer;
    private boolean bound;
    private boolean secure;
    private boolean rtcpMux;

    // RTP elements
    private final RtpClock clock;
    private final RtpClock oobClock;
    private RtpListener rtpListener;
    private RtpStatistics statistics;

    // Protocol handlers pipeline
    private static final int RTP_PRIORITY = 3; // a packet each 20ms
    private static final int STUN_PRIORITY = 2; // a packet each 400ms
    private static final int RTCP_PRIORITY = 1; // a packet each 5s

    private RtpHandler rtpHandler;
    private DtlsHandler dtlsHandler;
    private StunHandler stunHandler;
    private RtcpHandler rtcpHandler; // only used when rtcp-mux is enabled

    protected RtpTransport(int channelId, RtpStatistics statistics, RtpClock clock, RtpClock oobClock, Scheduler scheduler,
            UdpManager udpManager) {
        super();

        // Core elements
        this.scheduler = scheduler;
        this.udpManager = udpManager;
        this.heartBeat = new HeartBeat();
        
        // Channel attributes
        this.channelId = channelId;
        this.bound = false;
        this.secure = false;
        this.rtcpMux = false;

        // RTP elements
        this.clock = clock;
        this.oobClock = oobClock;
        this.statistics = statistics;
        this.rtpHandler = new RtpHandler(clock, statistics);
    }

    public int getChannelId() {
        return channelId;
    }

    public void setRtpListener(RtpListener listener) {
        this.rtpListener = listener;
    }

    public long getPacketsReceived() {
        return this.statistics.getRtpPacketsReceived();
    }

    public long getPacketsTransmitted() {
        return this.statistics.getRtpPacketsSent();
    }

    /**
     * Modifies the map between format and RTP payload number
     * 
     * @param rtpFormats the format map
     */
    public void setFormatMap(RTPFormats rtpFormats) {
        flush();
        this.rtpHandler.setFormatMap(rtpFormats);
        this.transmitter.setFormatMap(rtpFormats);
    }

    public RTPFormats getFormatMap() {
        return this.rtpHandler.getFormatMap();
    }

    /**
     * Sets the connection mode of the channel.<br>
     * Possible modes: send_only, recv_only, inactive, send_recv, conference, network_loopback.
     * 
     * @param connectionMode the new connection mode adopted by the channel
     */
    public void updateMode(ConnectionMode connectionMode) {
        switch (connectionMode) {
            case SEND_ONLY:
                this.rtpHandler.setReceivable(false);
                this.rtpHandler.setLoopable(false);
                break;
            case RECV_ONLY:
                this.rtpHandler.setReceivable(true);
                this.rtpHandler.setLoopable(false);
                break;
            case INACTIVE:
                this.rtpHandler.setReceivable(false);
                this.rtpHandler.setLoopable(false);
                break;
            case SEND_RECV:
            case CONFERENCE:
                this.rtpHandler.setReceivable(true);
                this.rtpHandler.setLoopable(false);
                break;
            case NETWORK_LOOPBACK:
                this.rtpHandler.setReceivable(false);
                this.rtpHandler.setLoopable(true);
                break;
            default:
                break;
        }

        boolean connectImmediately = false;
        if (this.remotePeer != null) {
            connectImmediately = udpManager.connectImmediately((InetSocketAddress) this.remotePeer);
        }

        if (udpManager.getRtpTimeout() > 0 && this.remotePeer != null && !connectImmediately) {
            if (this.rtpHandler.isReceivable()) {
                this.statistics.setLastHeartbeat(scheduler.getClock().getTime());
                scheduler.submitHeatbeat(heartBeat);
            } else {
                heartBeat.cancel();
            }
        }
    }

    private void onBinding(boolean useJitterBuffer) {
        // Set protocol handler priorities
        this.rtpHandler.setPipelinePriority(RTP_PRIORITY);
        if (this.rtcpMux) {
            this.rtcpHandler.setPipelinePriority(RTCP_PRIORITY);
        }
        if (this.secure) {
            this.stunHandler.setPipelinePriority(STUN_PRIORITY);
        }

        // Configure protocol handlers
        this.transmitter.setChannel(this.dataChannel);
        this.handlers.addHandler(this.rtpHandler);

        if (this.rtcpMux) {
            this.rtcpHandler.setChannel(this.dataChannel);
            this.handlers.addHandler(this.rtcpHandler);
        }

        if (this.secure) {
            this.dtlsHandler.setChannel(this.dataChannel);
            this.dtlsHandler.addListener(this);
            this.handlers.addHandler(this.stunHandler);

            // Start DTLS handshake
            this.dtlsHandler.handshake();
        }
    }

    public void bind(boolean isLocal) throws IOException {
        // Open this channel with UDP Manager on first available address
        this.selectionKey = udpManager.open(this);
        this.dataChannel = (DatagramChannel) this.selectionKey.channel();

        // activate media elements
        onBinding(!isLocal);

        // bind data channel
        this.udpManager.bind(this.dataChannel, PORT_ANY, isLocal);
        this.bound = true;
    }

    public void bind(DatagramChannel channel) throws IOException, SocketException {
        try {
            // Register the channel on UDP Manager
            this.selectionKey = udpManager.open(channel, this);
            this.dataChannel = channel;
        } catch (IOException e) {
            throw new SocketException(e.getMessage());
        }

        // activate media elements
        onBinding(true);

        // Only bind channel if necessary
        if (!channel.socket().isBound()) {
            this.udpManager.bind(channel, PORT_ANY);
        }
        this.bound = true;
    }

    public boolean isBound() {
        return this.bound;
    }

    public boolean isAvailable() {
        // The channel is available is is connected
        boolean available = this.dataChannel != null && this.dataChannel.isConnected();
        // In case of WebRTC calls the DTLS handshake must be completed
        if (this.secure) {
            available = available && this.dtlsHandler.isHandshakeComplete();
        }
        return available;
    }

    public void setRemotePeer(SocketAddress address) {
        this.remotePeer = address;
        boolean connectImmediately = false;
        if (this.dataChannel != null) {
            if (this.dataChannel.isConnected()) {
                try {
                    disconnect();
                } catch (IOException e) {
                    logger.error(e);
                }
            }

            connectImmediately = udpManager.connectImmediately((InetSocketAddress) address);
            if (connectImmediately) {
                try {
                    this.dataChannel.connect(address);
                } catch (IOException e) {
                    logger.info("Can not connect to remote address , please check that you are not using local address - 127.0.0.X to connect to remote");
                    logger.error(e.getMessage(), e);
                }
            }
        }

        if (udpManager.getRtpTimeout() > 0 && !connectImmediately) {
            if (this.rtpHandler.isReceivable()) {
                this.statistics.setLastHeartbeat(scheduler.getClock().getTime());
                scheduler.submitHeatbeat(heartBeat);
            } else {
                heartBeat.cancel();
            }
        }
    }

    public String getExternalAddress() {
        return this.udpManager.getExternalAddress();
    }

    public boolean hasExternalAddress() {
        return notEmpty(this.udpManager.getExternalAddress());
    }

    private boolean notEmpty(String text) {
        return text != null && !text.isEmpty();
    }

    public void enableSRTP(String hashFunction, String remotePeerFingerprint, IceAuthenticator authenticator) {
        this.secure = true;

        // setup the DTLS handler
        if (this.dtlsHandler == null) {
            this.dtlsHandler = new DtlsHandler();
        }
        this.dtlsHandler.setRemoteFingerprint(hashFunction, remotePeerFingerprint);

        // setup the STUN handler
        if (this.stunHandler == null) {
            this.stunHandler = new StunHandler(authenticator);
        }

        // Setup the RTP handler
        this.transmitter.enableSrtp(this.dtlsHandler);
        this.rtpHandler.enableSrtp(this.dtlsHandler);

        // Setup the RTCP handler. RTCP-MUX channels only!
        if (this.rtcpMux) {
            this.rtcpHandler.enableSRTCP(this.dtlsHandler);
        }
    }

    public void disableSRTP() {
        this.secure = false;

        // setup the DTLS handler
        if (this.dtlsHandler != null) {
            this.dtlsHandler.setRemoteFingerprint("", "");
            this.dtlsHandler.resetLocalFingerprint();
        }

        // setup the STUN handler
        if (this.stunHandler != null) {
            this.handlers.removeHandler(this.stunHandler);
        }

        // Setup the RTP handler
        this.transmitter.disableSrtp();
        this.rtpHandler.disableSrtp();

        // Setup the RTCP handler
        if (this.rtcpMux) {
            this.rtcpHandler.disableSRTCP();
        }
    }

    /**
     * Configures whether rtcp-mux is active in this channel or not.
     * 
     * @param enable decides whether rtcp-mux is to be enabled
     */
    public void enableRtcpMux(boolean enable) {
        this.rtcpMux = enable;

        // initialize handler if necessary
        if (enable && this.rtcpHandler == null) {
            this.rtcpHandler = new RtcpHandler(this.statistics);
        }
    }

    public Text getWebRtcLocalFingerprint() {
        if (this.dtlsHandler != null) {
            return this.dtlsHandler.getLocalFingerprint();
        }
        return new Text();
    }

    public void close() {
        if (rtcpMux) {
            this.rtcpHandler.leaveRtpSession();
            reset();
        } else {
            super.close();
            reset();
        }
        this.bound = false;
    }

    private void reset() {
        // Heartbeat reset
        heartBeat.cancel();

        // RTP reset
        this.rtpHandler.reset();
        this.transmitter.reset();

        // RTCP reset
        if (this.rtcpMux) {
            this.rtcpHandler.reset();
        }

        // DTLS reset
        if (this.secure) {
            this.dtlsHandler.reset();
        }
    }

    @Override
    public void onDtlsHandshakeComplete() {
        logger.info("DTLS handshake completed for RTP candidate.");
        if (this.rtcpMux) {
            this.rtcpHandler.joinRtpSession();
        }
    }

    @Override
    public void onDtlsHandshakeFailed(Throwable e) {
        this.rtpListener.onRtpFailure(e);
    }

    private class HeartBeat extends Task {

        @Override
        public int getQueueNumber() {
            return Scheduler.HEARTBEAT_QUEUE;
        }

        @Override
        public long perform() {
            long elapsedTime = scheduler.getClock().getTime() - statistics.getLastHeartbeat();
            if (elapsedTime > udpManager.getRtpTimeout() * 1000000000L) {
                if (rtpListener != null) {
                    rtpListener.onRtpFailure("RTP timeout! Elapsed time since last heartbeat: " + elapsedTime);
                }
            } else {
                scheduler.submitHeatbeat(this);
            }
            return 0;
        }
    }

    public void send(RtpPacket packet) {
        if (this.dataChannel.isConnected()) {
            // TODO send packet
        }
    }

    public void sendDtmf(RtpPacket packet) {
        if (this.dtmfSupported) {
            send(packet);
        }
    }

}