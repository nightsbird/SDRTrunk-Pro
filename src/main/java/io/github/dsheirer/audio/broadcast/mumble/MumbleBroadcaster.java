/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.audio.broadcast.mumble;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.AbstractAudioBroadcaster;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.audio.broadcast.IRealTimeAudioBroadcaster;
import io.github.dsheirer.audio.convert.InputAudioFormat;
import io.github.dsheirer.audio.convert.MP3Setting;
import io.github.dsheirer.audio.broadcast.mumble.proto.MumbleProto.Authenticate;
import io.github.dsheirer.audio.broadcast.mumble.proto.MumbleProto.ChannelState;
import io.github.dsheirer.audio.broadcast.mumble.proto.MumbleProto.Ping;
import io.github.dsheirer.audio.broadcast.mumble.proto.MumbleProto.ServerSync;
import io.github.dsheirer.audio.broadcast.mumble.proto.MumbleProto.UserState;
import io.github.dsheirer.audio.broadcast.mumble.proto.MumbleProto.Version;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.util.ThreadPool;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real-time audio broadcaster for Mumble servers via the Mumble protocol over TLS TCP.
 *
 * Implements IRealTimeAudioBroadcaster to receive 8 kHz mono float audio buffers
 * in real-time. Audio is upsampled to 16 kHz, Opus-encoded (VOIP application), and
 * sent as Mumble UDPTunnel voice packets containing an Opus voice payload.
 *
 * Mumble protocol overview (relevant subset):
 *   1. TLS TCP connection to server:port (default 64738).
 *   2. Exchange Version messages.
 *   3. Send Authenticate (username, password, opus=true).
 *   4. Receive ServerSync -> connection established, session ID known.
 *   5. Optionally join a channel by matching ChannelState names to the
 *      configured channel path, then sending a UserState with channel_id.
 *   6. Send UDPTunnel packets containing Mumble Opus voice frames.
 *   7. Last frame sets bit 13 of Opus length field to signal end of transmission.
 *
 * Voice packet wire format (Mumble "UDP audio" tunnelled over TCP control channel):
 *
 *     [ 1 byte  ] header = (type << 5) | target
 *                   type   = 4  (Opus audio)
 *                   target = 0  (normal talking)
 *     [ varint  ] sequence number
 *     [ varint  ] opus_length_field = (opus_bytes_len | talking_flag)
 *                   talking_flag bit 13 = 0 while talking, 1 on last frame
 *     [ N bytes ] raw opus frame
 *
 * Each Mumble TCP message is framed:
 *     [ 2 bytes big-endian ] message type
 *     [ 4 bytes big-endian ] payload length
 *     [ N bytes            ] protobuf payload
 */
public class MumbleBroadcaster extends AbstractAudioBroadcaster<MumbleConfiguration>
    implements IRealTimeAudioBroadcaster
{
    private static final Logger mLog = LoggerFactory.getLogger(MumbleBroadcaster.class);

    // ---- Opus / audio constants ----
    private static final int MUMBLE_SAMPLE_RATE  = 16_000;
    private static final int MUMBLE_CHANNELS     = 1;
    private static final int FRAME_SIZE_MS       = 10;
    private static final int FRAME_SIZE_SAMPLES  = MUMBLE_SAMPLE_RATE * FRAME_SIZE_MS / 1000; // 960
    private static final int OPUS_BITRATE        = 48_000;

    // ---- Mumble protocol message types ----
    private static final short MSG_VERSION       = 0;
    private static final short MSG_AUTHENTICATE  = 2;
    private static final short MSG_PING          = 3;
    //private static final short MSG_SERVER_SYNC   = 4;
    private static final short MSG_SERVER_SYNC   = 5;
    private static final short MSG_CHANNEL_STATE = 7;
    private static final short MSG_USER_STATE    = 9;
    private static final short MSG_UDP_TUNNEL    = 1;
    //private static final short MSG_CODEC_VERSION = 21;
    private static final short MSG_CODEC_VERSION = 21;
    private static final short MSG_PERMISSION_QUERY = 20;
    private static final short MSG_SUGGEST_CONFIG   = 24;
    private static final short MSG_SERVER_CONFIG    = 25;

    // ---- Mumble voice packet header ----
    private static final int OPUS_AUDIO_TYPE = 4;
    private static final int VOICE_TARGET    = 0;

    // ---- Reconnect / lifecycle ----
    private static final long RECONNECT_INTERVAL_MS = 15_000;

    private final AliasModel mAliasModel;

    private Socket           mSocket;
    private DataInputStream  mIn;
    private DataOutputStream mOut;

    private final AtomicBoolean mConnected    = new AtomicBoolean(false);
    private final AtomicBoolean mReconnecting = new AtomicBoolean(false);
    private final AtomicBoolean mStopped      = new AtomicBoolean(false);
    private final AtomicBoolean mStreamActive = new AtomicBoolean(false);
    private final AtomicInteger mSequence     = new AtomicInteger(0);

    private ScheduledFuture<?> mReconnectFuture;
    private ScheduledFuture<?> mPingFuture;
    private ScheduledFuture<?> mEncoderFuture;
    private Thread             mReaderThread;

    /** Session ID assigned by the server (needed for channel join). */
    private volatile int mSessionId = -1;
    /** Channel ID to join (resolved after we receive ChannelState messages). */
    private volatile int mTargetChannelId = -1;

    // ---- Audio pipeline ----
    private final LinkedTransferQueue<float[]> mAudioQueue = new LinkedTransferQueue<>();
    private OpusEncoder mOpusEncoder;
    private short[] mResampleBuffer   = new short[FRAME_SIZE_SAMPLES];
    private int     mResampleBufferPos = 0;
    private short   mPreviousSample    = 0;
    private byte[]  mOpusOutputBuffer  = new byte[1275];

    /** Guards the resampler / encoder state between the encoder task and stopRealTimeStream(). */
    private final Object mAudioLock = new Object();

    // ========================================================================
    // Constructor
    // ========================================================================

    public MumbleBroadcaster(MumbleConfiguration configuration, InputAudioFormat inputAudioFormat,
                             MP3Setting mp3Setting, AliasModel aliasModel)
    {
        super(configuration);
        mAliasModel = aliasModel;
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @Override
    public void start()
    {
        mStopped.set(false);

        if(mConnected.get())
        {
            mLog.debug("{}Mumble start() called but already connected, skipping", tag());
            return;
        }

        setBroadcastState(BroadcastState.CONNECTING);
        try
        {
            initOpusEncoder();
            connect();
        }
        catch(Exception e)
        {
            mLog.error("{}Error starting Mumble broadcaster", tag(), e);
            setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
            scheduleReconnect();
        }
    }

    @Override
    public void stop()
    {
        mStopped.set(true);
        if(mStreamActive.get()) stopRealTimeStream();
        cancelReconnect();
        cancelPing();
        mReconnecting.set(false);

        if(!mConnected.get())
        {
            disconnect();
            setBroadcastState(BroadcastState.DISCONNECTED);
        }
        else
        {
            mLog.debug("{}Mumble stop() -- preserving connection for restart", tag());
        }
    }

    @Override
    public void dispose()
    {
        mStopped.set(true);
        if(mStreamActive.get()) stopRealTimeStream();
        cancelReconnect();
        cancelPing();
        mReconnecting.set(false);
        disconnect();
        setBroadcastState(BroadcastState.DISCONNECTED);
    }

    @Override
    public int getAudioQueueSize()
    {
        return mAudioQueue.size();
    }

    /** Discard file-based recordings -- we use real-time streaming only. */
    @Override
    public void receive(AudioRecording audioRecording)
    {
        if(audioRecording != null) audioRecording.removePendingReplay();
    }

    // ========================================================================
    // IRealTimeAudioBroadcaster
    // ========================================================================

    @Override
    public boolean isRealTimeReady()
    {
        return mConnected.get() && !mStreamActive.get();
    }

    @Override
    public void startRealTimeStream(IdentifierCollection identifiers)
    {
        if(!mConnected.get())
        {
            mLog.warn("{}Cannot start Mumble stream - not connected", tag());
            return;
        }
        if(mStreamActive.get())
        {
            stopRealTimeStream();
        }

        mStreamActive.set(true);
        mSequence.set(0);
        mResampleBufferPos = 0;
        mPreviousSample    = 0;
        mAudioQueue.clear();

        if(mEncoderFuture == null || mEncoderFuture.isDone())
        {
            mEncoderFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(
                this::processAudioQueue, 10, 10, TimeUnit.MILLISECONDS);
        }

        mLog.info("{}Mumble stream started", tag());
    }

    @Override
    public void receiveRealTimeAudio(float[] audioBuffer)
    {
        if(mStreamActive.get()) mAudioQueue.offer(audioBuffer);
    }

    @Override
    public void stopRealTimeStream()
    {
        if(!mStreamActive.get()) return;

        mStreamActive.set(false);

        if(mEncoderFuture != null) { mEncoderFuture.cancel(false); mEncoderFuture = null; }

        synchronized(mAudioLock)
        {
            processAudioQueue();
            if(mResampleBufferPos > 0) flushResampleBuffer(true);
        }

        incrementStreamedAudioCount();
        broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE));
        mAudioQueue.clear();
        mLog.info("{}Mumble stream stopped", tag());
    }

    // ========================================================================
    // Audio Processing
    // ========================================================================

    private void processAudioQueue()
    {
        try
        {
            synchronized(mAudioLock)
            {
                float[] buffer;
                while((buffer = mAudioQueue.poll()) != null)
                {
                    processAudioBuffer(buffer);
                }
            }
        }
        catch(Exception e)
        {
            mLog.error("{}Error processing Mumble audio queue", tag(), e);
        }
    }

    /**
     * Upsample 8 kHz float audio to 16 kHz signed-short via linear interpolation,
     * accumulate into the 960-sample frame buffer, and Opus-encode full frames.
     */
    private void processAudioBuffer(float[] audio8k)
    {
        for(int i = 0; i < audio8k.length; i++)
        {
            short current  = (short)(audio8k[i] * 32767.0f);
            short midpoint = (short)((mPreviousSample + current) / 2);

            mResampleBuffer[mResampleBufferPos++] = midpoint;
            if(mResampleBufferPos >= FRAME_SIZE_SAMPLES)
            {
                encodeAndSendFrame(false);
                mResampleBufferPos = 0;
            }

            mResampleBuffer[mResampleBufferPos++] = current;
            if(mResampleBufferPos >= FRAME_SIZE_SAMPLES)
            {
                encodeAndSendFrame(false);
                mResampleBufferPos = 0;
            }

            mPreviousSample = current;
        }
    }

    private void flushResampleBuffer(boolean endOfTransmission)
    {
        if(mResampleBufferPos <= 0) return;
        for(int i = mResampleBufferPos; i < FRAME_SIZE_SAMPLES; i++)
            mResampleBuffer[i] = 0;
        encodeAndSendFrame(endOfTransmission);
        mResampleBufferPos = 0;
    }

    private void encodeAndSendFrame(boolean endOfTransmission)
    {
        if(!mConnected.get()) return;

        try
        {
            int encoded = mOpusEncoder.encode(mResampleBuffer, 0, FRAME_SIZE_SAMPLES,
                mOpusOutputBuffer, 0, mOpusOutputBuffer.length);

            if(encoded > 0)
            {
                sendVoicePacket(mOpusOutputBuffer, encoded, endOfTransmission);
            }
        }
        catch(Exception e)
        {
            mLog.error("{}Opus encoding error - reinitializing encoder", tag(), e);
            mResampleBufferPos = 0;
            try { initOpusEncoder(); }
            catch(Exception ex) { mLog.error("{}Failed to reinitialize Opus encoder", tag(), ex); }
        }
    }

    private void initOpusEncoder() throws Exception
    {
        mOpusEncoder = new OpusEncoder(MUMBLE_SAMPLE_RATE, MUMBLE_CHANNELS, OpusApplication.OPUS_APPLICATION_AUDIO);
        mOpusEncoder.setBitrate(OPUS_BITRATE);
        mOpusEncoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        mOpusEncoder.setComplexity(8);
        mLog.debug("{}Opus encoder initialized: {}Hz, {}ch, {}kbps, {}ms frames",
            tag(), MUMBLE_SAMPLE_RATE, MUMBLE_CHANNELS, OPUS_BITRATE / 1000, FRAME_SIZE_MS);
    }

    // ========================================================================
    // TCP / Mumble Protocol
    // ========================================================================

    private void connect()
    {
        if(!mReconnecting.compareAndSet(false, true)) return;
        if(mStopped.get()) { mReconnecting.set(false); return; }

        disconnect();

        MumbleConfiguration config = getBroadcastConfiguration();
        String host = config.getHost();
        int    port = config.getPort();

        if(host == null || host.isBlank())
        {
            mLog.error("{}Mumble host is not configured", tag());
            setBroadcastState(BroadcastState.CONFIGURATION_ERROR);
            mReconnecting.set(false);
            return;
        }

        mLog.debug("{}Connecting to Mumble server {}:{}", tag(), host, port);
        ThreadPool.SCHEDULED.schedule(() -> {
            try
            {
                //SSLContext ssl = SSLContext.getInstance("TLS");
                //ssl.init(null, new TrustManager[]{new PermissiveTrustManager()}, null);

                //mSocket = ssl.getSocketFactory().createSocket(host, port);
try
{
    SSLContext ssl = SSLContext.getInstance("TLS");
    ssl.init(null, new TrustManager[]{new PermissiveTrustManager()}, null);
    mSocket = ssl.getSocketFactory().createSocket(host, port);
    mLog.info("{}Connected via TLS", tag());
}
catch(Exception tlsEx)
{
    mLog.info("{}TLS failed ({}), retrying with plain TCP", tag(), tlsEx.getMessage());
    mSocket = new java.net.Socket(host, port);
    mLog.info("{}Connected via plain TCP", tag());
}
                mSocket.setTcpNoDelay(true);
                mSocket.setSoTimeout(0);

                mOut = new DataOutputStream(mSocket.getOutputStream());
                mIn  = new DataInputStream(mSocket.getInputStream());

                // START READER FIRST before sending anything, so we don't miss
		// the server's immediate response burst including ServerSync
		mReaderThread = new Thread(this::readLoop, "Mumble-Reader");
                mReaderThread.setDaemon(true);
                mReaderThread.start();

                mPingFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(
                    this::sendPing, 5, 5, TimeUnit.SECONDS);

                // Now send handshake — reader is already listening
		sendVersion();
                sendAuthenticate();

                mReconnecting.set(false);
            }
            catch(Exception e)
            {
                mLog.error("{}Mumble connection failed: {}", tag(), e.getMessage());
                setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                mReconnecting.set(false);
                scheduleReconnect();
            }
        }, 0, TimeUnit.MILLISECONDS);
    }

    private void disconnect()
    {
        mConnected.set(false);
        mSessionId       = -1;
        mTargetChannelId = -1;
        cancelPing();
        if(mReaderThread != null) { mReaderThread.interrupt(); mReaderThread = null; }
        if(mSocket != null)
        {
            try { mSocket.close(); } catch(Exception e) { /* ignore */ }
            mSocket = null;
        }
        mIn  = null;
        mOut = null;
    }

    private void scheduleReconnect()
    {
        if(mReconnectFuture != null && !mReconnectFuture.isDone()) return;
        mReconnectFuture = ThreadPool.SCHEDULED.schedule(() -> {
            if(!mConnected.get() && !mStopped.get())
            {
                mLog.debug("{}Mumble reconnecting...", tag());
                connect();
            }
        }, RECONNECT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelReconnect()
    {
        if(mReconnectFuture != null) { mReconnectFuture.cancel(true); mReconnectFuture = null; }
    }

    private void cancelPing()
    {
        if(mPingFuture != null) { mPingFuture.cancel(true); mPingFuture = null; }
    }

    // ========================================================================
    // Reader thread
    // ========================================================================

    private void readLoop()
    {
        try
        {
            while(!Thread.currentThread().isInterrupted() && mSocket != null && !mSocket.isClosed())
            {
                short  type    = mIn.readShort();
                int    length  = mIn.readInt();
                byte[] payload = new byte[length];
                mIn.readFully(payload);
                handleControlMessage(type, payload);
            }
        }
        catch(Exception e)
        {
            if(!mStopped.get() && !Thread.currentThread().isInterrupted())
            {
                mLog.warn("{}Mumble read error: {} -- reconnecting", tag(), e.getMessage());
                mConnected.set(false);
                setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                scheduleReconnect();
            }
        }
    }

    private void handleControlMessage(short type, byte[] payload)
    {
	mLog.info("{}Message type={} length={}", tag(), type, payload.length);
        try
        {
            if(type == MSG_SERVER_SYNC)
            {
                ServerSync sync = ServerSync.parseFrom(payload);
                mSessionId = (int)sync.getSession();
                mConnected.set(true);
                setBroadcastState(BroadcastState.CONNECTED);
                mLog.info("{}Mumble connected (session={})", tag(), mSessionId);
                joinConfiguredChannel();
            }
            else if(type == MSG_CHANNEL_STATE)
            {
                ChannelState cs = ChannelState.parseFrom(payload);
                if(mTargetChannelId < 0) resolveChannelId(cs);
            }
            else if(type == MSG_CODEC_VERSION)
            {
                mLog.debug("{}Mumble CodecVersion received", tag());
            }
            // All other message types ignored
        }
        catch(Exception e)
        {
            mLog.error("{}Error parsing Mumble message type {}", tag(), type, e);
        }
    }

    // ========================================================================
    // Channel resolution & join
    // ========================================================================

    private void resolveChannelId(ChannelState cs)
    {
        String configuredPath = getBroadcastConfiguration().getChannel();
        if(configuredPath == null || configuredPath.isBlank()) return;

        String[] parts = configuredPath.split("/");
        String targetName = parts[parts.length - 1].trim();

        if(cs.hasName() && cs.getName().equalsIgnoreCase(targetName))
        {
            mTargetChannelId = (int)cs.getChannelId();
            mLog.debug("{}Resolved Mumble channel '{}' -> id={}", tag(), targetName, mTargetChannelId);
            joinConfiguredChannel();
        }
    }

    private void joinConfiguredChannel()
    {
        if(mSessionId < 0 || mTargetChannelId < 0) return;

        try
        {
            UserState us = UserState.newBuilder()
                .setSession(mSessionId)
                .setChannelId(mTargetChannelId)
                .build();
            sendControlMessage(MSG_USER_STATE, us.toByteArray());
            mLog.info("{}Joined Mumble channel id={}", tag(), mTargetChannelId);
        }
        catch(Exception e)
        {
            mLog.error("{}Failed to join Mumble channel", tag(), e);
        }
    }

    // ========================================================================
    // Mumble protocol senders
    // ========================================================================

    private void sendVersion() throws IOException
    {
        Version v = Version.newBuilder()
            .setVersionV1(0x010500) // 1.5.0
            .setRelease("SDRTrunk Mumble Gateway")
            .setOs("Java")
            .setOsVersion(System.getProperty("java.version", "unknown"))
            .build();
        sendControlMessage(MSG_VERSION, v.toByteArray());
    }

    private void sendAuthenticate() throws IOException
    {
        MumbleConfiguration config = getBroadcastConfiguration();
        Authenticate.Builder auth = Authenticate.newBuilder()
            .setUsername(config.getUsername())
            .setOpus(true);

        String password = config.getPassword();
        if(password != null && !password.isBlank()) auth.setPassword(password);

        sendControlMessage(MSG_AUTHENTICATE, auth.build().toByteArray());
    }

    private void sendPing()
    {
        if(!mConnected.get()) return;
        try
        {
            Ping ping = Ping.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .build();
            sendControlMessage(MSG_PING, ping.toByteArray());
        }
        catch(Exception e)
        {
            mLog.error("{}Mumble ping error", tag(), e);
        }
    }

    private synchronized void sendControlMessage(short type, byte[] payload) throws IOException
    {
        if(mOut == null) throw new IOException("Not connected");
        mOut.writeShort(type);
        mOut.writeInt(payload.length);
        mOut.write(payload);
        mOut.flush();
    }

    private void sendVoicePacket(byte[] opusData, int opusLen, boolean endOfTransmission)
    {
        if(!mConnected.get() || mOut == null) return;

        try
        {
            int seq         = mSequence.getAndIncrement();
            int lengthField = opusLen;
            if(endOfTransmission) lengthField |= 0x2000;

            byte[] varSeq = encodeVarint(seq);
            byte[] varLen = encodeVarint(lengthField);

            int    payloadSize = 1 + varSeq.length + varLen.length + opusLen;
            byte[] payload     = new byte[payloadSize];
            int pos = 0;

            payload[pos++] = (byte)((OPUS_AUDIO_TYPE << 5) | VOICE_TARGET);
            System.arraycopy(varSeq, 0, payload, pos, varSeq.length); pos += varSeq.length;
            System.arraycopy(varLen, 0, payload, pos, varLen.length); pos += varLen.length;
            System.arraycopy(opusData, 0, payload, pos, opusLen);

            sendControlMessage(MSG_UDP_TUNNEL, payload);
        }
        catch(Exception e)
        {
            if(mConnected.get())
            {
                mLog.error("{}Error sending Mumble voice packet", tag(), e);
                mConnected.set(false);
                setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                scheduleReconnect();
            }
        }
    }

    // ========================================================================
    // Mumble varint encoding
    // ========================================================================

    private static byte[] encodeVarint(long value)
    {
        if(value < 0x80)
        {
            return new byte[]{(byte)value};
        }
        else if(value < 0x4000)
        {
            return new byte[]{
                (byte)(0x80 | (value >> 8)),
                (byte)(value & 0xFF)
            };
        }
        else if(value < 0x200000)
        {
            return new byte[]{
                (byte)(0xC0 | (value >> 16)),
                (byte)((value >> 8) & 0xFF),
                (byte)(value & 0xFF)
            };
        }
        else if(value < 0x10000000)
        {
            return new byte[]{
                (byte)(0xE0 | (value >> 24)),
                (byte)((value >> 16) & 0xFF),
                (byte)((value >> 8) & 0xFF),
                (byte)(value & 0xFF)
            };
        }
        else
        {
            return new byte[]{
                (byte)0xF0,
                (byte)((value >> 24) & 0xFF),
                (byte)((value >> 16) & 0xFF),
                (byte)((value >> 8) & 0xFF),
                (byte)(value & 0xFF)
            };
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private String tag()
    {
        MumbleConfiguration cfg = getBroadcastConfiguration();
        String host = (cfg != null && cfg.getHost() != null) ? cfg.getHost() : "?";
        return "[" + host + "] ";
    }

    // ========================================================================
    // Permissive TLS trust manager (accepts self-signed Mumble server certs)
    // ========================================================================

    private static class PermissiveTrustManager implements X509TrustManager
    {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
