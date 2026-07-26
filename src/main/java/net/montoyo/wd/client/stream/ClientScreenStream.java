package net.montoyo.wd.client.stream;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.montoyo.wd.core.IScreenStream;
import net.montoyo.wd.net.WDNetworkRegistry;
import net.montoyo.wd.net.client_bound.S2CMessageStreamFrame;
import net.montoyo.wd.net.server_bound.C2SMessageStreamCtrl;
import net.montoyo.wd.serverbrowser.ScreenKey;
import net.montoyo.wd.serverbrowser.ServerBrowserManager;
import net.montoyo.wd.serverbrowser.ServerScreenBrowser;
import net.montoyo.wd.serverbrowser.StreamedScreen;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.video.StreamCodec;
import net.montoyo.wd.video.VideoDecoder;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL12.*;

/**
 * Client-side receiver for one screen's video stream:
 * reassembles chunks, decodes VP8/VP9 (per the codec tag on each frame) and
 * uploads BGRA frames to a GL texture.
 *
 * In single-player (integrated server, same JVM) it bypasses the codec
 * entirely and copies frames straight out of the server-side browser.
 */
public class ClientScreenStream implements IScreenStream {
    private static final long KEYFRAME_REQUEST_INTERVAL_MS = 500;
    /** Frames allowed in the decode queue before we drop + resync (slow client). */
    private static final int MAX_PENDING_DECODES = 8;

    /**
     * All video decoding runs here instead of the render thread: decoding a
     * 30 fps stream inline costs several ms per frame, tanks the client's fps
     * and makes every OTHER packet (audio!) batch up between render frames.
     */
    private static final java.util.concurrent.ExecutorService DECODER_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread th = new Thread(r, "WDT-VideoDecode");
                th.setDaemon(true);
                return th;
            });

    private final ScreenKey key;
    private volatile boolean closed = false;

    // GL (render thread only)
    private int textureId = 0;
    private int texW = -1, texH = -1;
    private volatile boolean hasFrame = false;

    // Remote (network) path - main thread
    private int streamId = Integer.MIN_VALUE;
    private volatile boolean waitingKeyframe = true;
    private final java.util.concurrent.atomic.AtomicLong lastKeyframeRequest = new java.util.concurrent.atomic.AtomicLong();
    private int lastFrameSeq = -1;
    private final java.util.concurrent.atomic.AtomicInteger pendingDecodes = new java.util.concurrent.atomic.AtomicInteger();

    // Decoder state - decode thread only
    private VideoDecoder decoder;
    private boolean decoderFailed = false;
    private int decoderStreamId = Integer.MIN_VALUE;
    private byte decoderCodec = -1;

    // Latest decoded frame, handed from the decode thread to the render thread
    private final Object frameLock = new Object();
    private ByteBuffer readyFrame; // guarded by frameLock, reused
    private int readyW, readyH; // guarded by frameLock
    private boolean readyFresh = false; // guarded by frameLock

    // Chunk reassembly (main thread)
    private int pendingSeq = -1;
    private byte[][] pendingChunks;
    private int pendingReceived;

    // Delivery feedback (main thread): measures the ARRIVAL cadence of
    // complete frames. On TCP nothing is lost, so congestion shows up as
    // frames arriving late in bursts; each large gap is one visible stutter.
    private static final long STALL_GAP_MS = 180;
    private long fbLastArrivalMs = 0;
    private int fbFrames = 0;
    private int fbStalls = 0;

    // Presentation pacing (main thread). Frames are shown ~300ms late on
    // purpose: they wait in this buffer and are handed to the decoder at a
    // steady cadence, so network jitter is absorbed by the buffer instead of
    // showing up as doubled/skipped frames. Frames are buffered ENCODED
    // (~10KB each, vs ~1-4MB decoded) and must be decoded strictly in order
    // (delta frames reference the previous frame), so catch-up decodes faster
    // rather than dropping.
    private static final int PREBUFFER_FRAMES = 8;    // ~270ms at 30 fps
    private static final long MAX_HOLD_MS = 350;      // bound on added latency
    private static final int MAX_BUFFER_FRAMES = 24;  // ~0.8s: hard catch-up beyond this
    private static final long SPARSE_INTERVAL_MS = 80; // slower arrivals = page paints sporadically

    private record BufferedFrame(byte[] data, int streamId, byte codec, long arrivalMs) {
    }

    private final java.util.ArrayDeque<BufferedFrame> frameBuffer = new java.util.ArrayDeque<>();
    private double arrivalEmaMs = 0;
    private boolean paced = false;
    private long nextDecodeDueMs = 0;
    private long lastSubmitMs = 0;

    // One-shot diagnostics
    private boolean firstPacketLogged = false;
    private boolean firstDecodeLogged = false;

    // Local direct path (single-player / LAN host)
    private ServerScreenBrowser.FrameCopy localCopy;
    private long localCounter = 0;

    ClientScreenStream(ScreenKey key) {
        this.key = key;
    }

    public ScreenKey getKey() {
        return key;
    }

    /** Called on the render thread with a received frame chunk. */
    void feed(S2CMessageStreamFrame msg) {
        if (closed)
            return;

        if (!firstPacketLogged) {
            firstPacketLogged = true;
            Log.info("Screen stream %s: receiving video from the server (streamId=%d, keyframe=%b, %d chunk(s))",
                    key, msg.streamId, msg.keyframe, msg.chunkCount & 0xFF);
        }

        if (msg.streamId != streamId) {
            // Browser was (re)created server-side: reset decoder state
            streamId = msg.streamId;
            waitingKeyframe = true;
            pendingSeq = -1;
            lastFrameSeq = -1;
            frameBuffer.clear();
            paced = false;
        }

        byte[] data;
        if (msg.chunkCount <= 1) {
            data = msg.data;
        } else {
            if (msg.seq != pendingSeq) {
                pendingSeq = msg.seq;
                pendingChunks = new byte[msg.chunkCount & 0xFF][];
                pendingReceived = 0;
            }

            int idx = msg.chunkIdx & 0xFF;
            if (idx >= pendingChunks.length || pendingChunks[idx] != null)
                return;

            pendingChunks[idx] = msg.data;
            pendingReceived++;
            if (pendingReceived < pendingChunks.length)
                return;

            int total = 0;
            for (byte[] c : pendingChunks)
                total += c.length;

            data = new byte[total];
            int off = 0;
            for (byte[] c : pendingChunks) {
                System.arraycopy(c, 0, data, off, c.length);
                off += c.length;
            }
            pendingSeq = -1;
            pendingChunks = null;
        }

        // Exact duplicate of the frame we already processed (e.g. a packet
        // delivered twice): ignore it entirely (not even counted as an
        // arrival). Treating it as a sequence gap would trigger a pointless
        // keyframe resync on every frame.
        if (lastFrameSeq != -1 && msg.seq == lastFrameSeq)
            return;

        // Track the arrival cadence of complete frames (even ones we end up
        // dropping below: they still arrived, which is what the network
        // feedback cares about).
        long arrivalMs = System.currentTimeMillis();
        if (fbLastArrivalMs != 0) {
            long gap = arrivalMs - fbLastArrivalMs;
            if (gap >= STALL_GAP_MS)
                fbStalls++;
            if (gap <= 250) // ignore idle gaps; this estimates the streaming frame period
                arrivalEmaMs = arrivalEmaMs == 0 ? gap : arrivalEmaMs * 0.9 + gap * 0.1;
        }
        fbLastArrivalMs = arrivalMs;
        fbFrames++;

        // A gap in the frame sequence means the decoder's reference frame no
        // longer matches the encoder's: every following delta frame would
        // smear "ghosts" over the picture until the next keyframe. Resync
        // immediately instead of displaying garbage. lastFrameSeq is only
        // advanced for frames actually handed to the decoder, so frames WE
        // drop (below) register as a gap too.
        if (!msg.keyframe && lastFrameSeq != -1 && msg.seq != lastFrameSeq + 1)
            waitingKeyframe = true;

        if (waitingKeyframe && !msg.keyframe) {
            requestKeyframe();
            return;
        }

        // Only cleared here, on the main thread, when a keyframe is actually
        // accepted into the pipeline. (Clearing it from the decode thread
        // after any successful decode used to re-enable delta frames while an
        // older queued frame was still in flight - the classic ghosting bug.)
        if (msg.keyframe)
            waitingKeyframe = false;
        lastFrameSeq = msg.seq;

        frameBuffer.addLast(new BufferedFrame(data, msg.streamId, msg.codec, arrivalMs));
        pumpFrameBuffer();
    }

    /**
     * Feeds buffered frames to the decoder. While the stream runs fast
     * (video), frames go out at a steady one-frame-period cadence after a
     * short prebuffer; sporadic paints (static/interactive pages) skip the
     * buffering entirely. Main thread only; called on arrival, every render
     * frame and every client tick, so pacing continues between arrivals.
     */
    void pumpFrameBuffer() {
        if (closed)
            return;

        long now = System.currentTimeMillis();

        if (frameBuffer.isEmpty()) {
            // Buffer ran dry (true underrun, not just consumed-on-schedule):
            // rebuild the cushion before resuming, otherwise we keep playing
            // at zero margin and every arrival hiccup shows.
            if (paced && lastSubmitMs != 0 && now - lastSubmitMs > 250)
                paced = false;
            return;
        }

        // Sporadic paints: pacing would only add latency to page updates.
        if (arrivalEmaMs == 0 || arrivalEmaMs > SPARSE_INTERVAL_MS) {
            while (!frameBuffer.isEmpty())
                if (!submitFrame(frameBuffer.removeFirst(), now))
                    return;
            paced = false;
            return;
        }

        if (!paced) {
            BufferedFrame oldest = frameBuffer.peekFirst();
            if (frameBuffer.size() < PREBUFFER_FRAMES && now - oldest.arrivalMs() < MAX_HOLD_MS)
                return; // still building the jitter cushion
            paced = true;
            nextDecodeDueMs = now;
        }

        long period = Math.max(15, Math.min(67, Math.round(arrivalEmaMs)));

        if (now >= nextDecodeDueMs) {
            if (!submitFrame(frameBuffer.removeFirst(), now))
                return;
            // max() forgives pauses; when behind, this paces at ~2x for a
            // gentle catch-up instead of dumping the whole backlog at once.
            nextDecodeDueMs = Math.max(nextDecodeDueMs, now - period) + period;
        }

        // Way too much buffered (long stall then a burst): decode the excess
        // immediately. Frames cannot be dropped (delta frames reference their
        // predecessor), but decoding is cheap - the picture just jumps ahead.
        while (frameBuffer.size() > MAX_BUFFER_FRAMES)
            if (!submitFrame(frameBuffer.removeFirst(), now))
                return;
    }

    /** Hands one frame to the decode thread. Returns false if the pipeline resynced. */
    private boolean submitFrame(BufferedFrame f, long now) {
        // Decode queue full (client slower than the stream): drop everything
        // and resync off a fresh keyframe rather than falling behind.
        if (pendingDecodes.get() >= MAX_PENDING_DECODES) {
            waitingKeyframe = true;
            frameBuffer.clear();
            paced = false;
            requestKeyframe();
            return false;
        }

        lastSubmitMs = now;
        pendingDecodes.incrementAndGet();
        DECODER_POOL.execute(() -> decodeAsync(f.data(), f.streamId(), f.codec()));
        return true;
    }

    /** Runs on the decode thread; owns {@link #decoder}. */
    private void decodeAsync(byte[] data, int sid, byte codec) {
        pendingDecodes.decrementAndGet();
        if (closed || decoderFailed)
            return;

        if (sid != decoderStreamId || codec != decoderCodec) {
            decoderStreamId = sid;
            decoderCodec = codec;
            if (decoder != null) {
                decoder.close();
                decoder = null;
            }
        }

        if (decoder == null) {
            // Natives still loading: drop the frame instead of triggering the codec
            // class initialization too early (a failure would poison it for good).
            // The keyframe-request logic recovers the picture once loading is done.
            if (!net.montoyo.wd.WebDisplays.isFfmpegReady()) {
                if (net.montoyo.wd.WebDisplays.hasFfmpegFailed()) {
                    decoderFailed = true;
                    Log.errorEx("Cannot decode screen video: the FFmpeg natives failed to load (see startup log).", null);
                }
                return;
            }

            try {
                decoder = new VideoDecoder(StreamCodec.fromWireId(codec));
            } catch (Throwable t) {
                decoderFailed = true; // don't retry (and re-log) for every incoming frame
                Log.errorEx("Failed to create the video decoder; this screen cannot display video. "
                        + "See the log above for the underlying FFmpeg loading problem.", t);
                return;
            }
        }

        VideoDecoder.DecodedFrame frame = decoder.decode(data);
        if (frame == null) {
            // Corrupt/failed frame: the reference chain is broken, resync
            waitingKeyframe = true;
            requestKeyframe();
            return;
        }

        synchronized (frameLock) {
            int needed = frame.width * frame.height * 4;
            if (readyFrame == null || readyFrame.capacity() < needed)
                readyFrame = ByteBuffer.allocateDirect(needed);

            frame.buffer.position(0).limit(needed);
            readyFrame.position(0).limit(needed);
            readyFrame.put(frame.buffer);
            readyW = frame.width;
            readyH = frame.height;
            readyFresh = true;
        }

        if (!firstDecodeLogged) {
            firstDecodeLogged = true;
            Log.info("Screen stream %s: first frame decoded (%dx%d), the screen is now displaying video.",
                    key, frame.width, frame.height);
        }
    }

    /**
     * Returns {completeFramesReceived, stallGaps} since the last poll and
     * resets the window. Main thread only.
     */
    int[] pollFeedback() {
        int[] out = {fbFrames, fbStalls};
        fbFrames = 0;
        fbStalls = 0;
        return out;
    }

    /** Thread-safe: called from the main thread and the decode thread. */
    private void requestKeyframe() {
        requestKeyframe(KEYFRAME_REQUEST_INTERVAL_MS);
    }

    private void requestKeyframe(long minIntervalMs) {
        long now = System.currentTimeMillis();
        long last = lastKeyframeRequest.get();
        if (now - last >= minIntervalMs && lastKeyframeRequest.compareAndSet(last, now))
            WDNetworkRegistry.INSTANCE.sendToServer(new C2SMessageStreamCtrl(key.pos(), key.side(), C2SMessageStreamCtrl.ACT_KEYFRAME));
    }

    @Override
    public int pollTextureId() {
        if (closed)
            return 0;

        // Single-player direct path: read frames straight from the in-JVM server browser
        if (Minecraft.getInstance().hasSingleplayerServer()) {
            StreamedScreen local = ServerBrowserManager.peek(key);
            if (local != null) {
                ServerScreenBrowser browser = local.getBrowser();
                if (browser != null) {
                    ServerScreenBrowser.FrameCopy copy = browser.copyFrame(localCopy, localCounter);
                    if (copy != null) {
                        localCopy = copy;
                        localCounter = copy.counter;
                        upload(copy.buffer, copy.width, copy.height);
                    }
                }
            }
        } else {
            // Keep the presentation pacing running between packet arrivals
            pumpFrameBuffer();

            // Upload the newest decoded frame, if the decode thread produced one
            synchronized (frameLock) {
                if (readyFresh) {
                    readyFresh = false;
                    upload(readyFrame, readyW, readyH);
                }
            }

            if (!hasFrame) {
                // Never displayed anything yet: the initial keyframe may have been
                // lost (skippable packets, decoder hiccup on join). For static pages
                // the server produces no further frames on its own, so nudge it.
                requestKeyframe(2000);
            }
        }

        return hasFrame ? textureId : 0;
    }

    @Override
    public boolean hasFrame() {
        return hasFrame;
    }

    private void upload(ByteBuffer bgra, int width, int height) {
        if (textureId == 0) {
            textureId = glGenTextures();
            RenderSystem.bindTexture(textureId);
            RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        } else {
            RenderSystem.bindTexture(textureId);
        }

        bgra.position(0);
        RenderSystem.pixelStore(GL_UNPACK_ROW_LENGTH, width);
        RenderSystem.pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
        RenderSystem.pixelStore(GL_UNPACK_SKIP_ROWS, 0);

        if (width == texW && height == texH) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, bgra);
        } else {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, bgra);
            texW = width;
            texH = height;
        }

        RenderSystem.pixelStore(GL_UNPACK_ROW_LENGTH, 0);
        RenderSystem.bindTexture(0);
        hasFrame = true;
    }

    @Override
    public void close() {
        if (closed)
            return;
        closed = true;

        frameBuffer.clear();
        ClientStreamManager.onStreamClosed(this);

        if (textureId != 0) {
            glDeleteTextures(textureId);
            textureId = 0;
        }

        // The decoder is owned by the decode thread; free it there
        DECODER_POOL.execute(() -> {
            if (decoder != null) {
                decoder.close();
                decoder = null;
            }
            decoderStreamId = Integer.MIN_VALUE;
            decoderCodec = -1;
        });

        hasFrame = false;
    }
}
