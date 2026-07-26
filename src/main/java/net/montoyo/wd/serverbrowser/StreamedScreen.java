package net.montoyo.wd.serverbrowser;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import net.montoyo.wd.audio.OpusEncoder;
import net.montoyo.wd.audio.PcmConverter;
import net.montoyo.wd.client.audio.ScreenAudioManager;
import net.montoyo.wd.config.CommonConfig;
import net.montoyo.wd.controls.builtin.ClickControl;
import net.montoyo.wd.net.WDNetworkRegistry;
import net.montoyo.wd.net.client_bound.S2CMessageStreamAudio;
import net.montoyo.wd.net.client_bound.S2CMessageStreamFrame;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.serialization.TypeData;
import net.montoyo.wd.utilities.math.Vector2i;
import net.montoyo.wd.video.StreamCodec;
import net.montoyo.wd.video.VideoEncoder;
import org.cef.CefClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One server-side browser + video encoder + set of subscribed players.
 * The browser runs headless on the server; encoded frames are pushed to all
 * remote subscribers. Local (same-JVM) subscribers read frames directly from
 * the browser and are only counted for lifetime management.
 */
public final class StreamedScreen {
    public final ScreenKey key;
    public final int streamId;

    private volatile ServerScreenBrowser browser;
    private volatile boolean browserRequested = false;
    private volatile String url;
    private volatile int browserW, browserH;

    // The encode pool has multiple threads: encodeTick and the close task can
    // run concurrently, and freeing FFmpeg contexts mid-encode segfaults the
    // whole JVM (observed as SIGSEGV in libswscale). Everything that touches
    // `encoder` must hold this lock.
    private final Object encoderLock = new Object();
    private VideoEncoder encoder; // guarded by encoderLock
    private int encoderKbps = 0; // guarded by encoderLock
    private ServerScreenBrowser.FrameCopy grab; // encoder thread only
    private long grabCounter = 0; // encoder thread only
    private long lastContentChangeMs = 0; // encoder thread only
    private int seq = 0; // encoder thread only
    private volatile boolean keyframeNeeded = true;

    /**
     * After the page content changes (or a keyframe is requested), keep
     * re-encoding the same frame for this long. Static pages would otherwise
     * be stuck forever with the initial keyframe, whose quality is capped by
     * the per-frame bitrate budget (very coarse); the extra delta frames let
     * the rate control refine the picture to near-lossless within a second.
     */
    private static final long REFINE_WINDOW_MS = 3000;

    private final ConcurrentHashMap<UUID, Subscriber> subscribers = new ConcurrentHashMap<>();
    volatile long lastViewerTime = System.currentTimeMillis();
    private boolean firstFrameLogged = false; // encoder thread only

    // Browser liveness watchdog. Chromium render processes do die after hours
    // of uptime (OOM on heavy pages is the usual culprit); the CefBrowser
    // object survives and silently swallows injected input, so without this
    // the screen freezes on its last frame and ignores the mouse until the
    // screen is rebuilt by hand.
    private volatile long browserCreatedMs = 0;
    private volatile long lastInputMs = 0;
    private long probeSentMs = 0;    // server thread only
    private long lastRecoveryMs = 0; // server thread only
    /** Input with no paint after this long means the page ignored a click - suspicious. */
    private static final long INPUT_SILENCE_MS = 6_000;
    /** With viewers present, even static pages should answer a probe this often. */
    private static final long IDLE_PROBE_MS = 60_000;
    /** A live renderer answers an invalidate() with a paint well within this. */
    private static final long PROBE_TIMEOUT_MS = 8_000;
    /** Cooldown between browser recreations so a persistently broken page cannot loop. */
    private static final long RECOVERY_COOLDOWN_MS = 30_000;

    private volatile ScheduledFuture<?> encodeTask;
    private volatile boolean encodeStarted = false;
    private volatile boolean encodeBroken = false;
    private long encodePeriodNs; // set once in startEncoding
    private long lastEncodeNs = 0; // encoder thread only
    /**
     * Token-bucket pacing deadline: the next encode may not start before
     * (roughly) this time. Keeps the long-run send rate at exactly the
     * stream fps even when Chromium paints faster (e.g. 60fps pages);
     * without it the paint-driven loop happily encoded ~35-45 fps, wasting
     * bandwidth and making frame arrival irregular on the client.
     */
    private long nextAllowedNs = 0; // encoder thread only
    /** Pacing tolerance so a paint arriving a hair early is not pushed a whole frame late. */
    private static final long PACE_SLACK_NS = 6_000_000L;
    private volatile boolean closed = false;

    // encodeTick outcomes, used to pick the next wakeup delay
    private static final int TICK_ENCODED = 0; // sent a frame; wake for the next paint
    private static final int TICK_WAITING = 1; // a frame is imminent; poll quickly
    private static final int TICK_IDLE = 2;    // nothing to do; poll at the frame period
    /** Fast poll interval while waiting for the next browser paint. */
    private static final long POLL_NS = 3_000_000L;

    // Adaptive resolution + diagnostics (encoder thread only). The startup
    // benchmark only extrapolates from a small test encode; the real cost per
    // frame (with Chromium competing for the same cores) is measured here,
    // and the stream resolution is scaled down when encoding cannot keep up
    // with the target fps - a smaller smooth picture beats a big jerky one.
    private double encodeEmaMs = 0;
    private int encodeSamples = 0;
    private double cpuScale = 1.0;
    private long lastAdaptMs = 0;
    private long lastOutPixels = 0;
    private long lastHardCapPixels = Long.MAX_VALUE;
    private long lastCpuCapPixels = 0;

    // Network adaptation (AIMD, driven by per-viewer delivery reports).
    // The encoder can be perfectly healthy while the network path to a viewer
    // cannot carry the stream: frames queue up in TCP, arrive in bursts (seen
    // as ~10 fps stutter) and starve the audio behind them (crackling).
    // Only the client can observe that, so viewers report their delivery
    // quality every 2s and the stream steps down until the worst viewer's
    // link keeps up - on any server, without manual tuning.
    private volatile double netScale = 1.0; // written on server thread, read by encoder thread
    private long lastNetBadMs = 0;    // server thread only
    private long lastNetChangeMs = 0; // server thread only
    /** Rolling send cadence, used to ignore video-stall reports for slow/static pages. */
    private volatile double sendIntervalEmaMs = 0;
    private volatile long lastSentWallMs = 0;
    private long prevSentWallMs = 0; // encoder thread only
    private long statWindowStart = 0;
    private int statFrames = 0;
    private long statBytes = 0;
    private double statEncodeMs = 0;

    // Mouse state (mirrors the old client-side ScreenBlockEntity.handleMouseEvent)
    private final Vector2i lastMousePos = new Vector2i();

    // Audio (CEF audio thread only, except the volatile format fields)
    private volatile int audioRate = 48000;
    private volatile int audioChannels = 1;
    private final Object audioLock = new Object();
    private OpusEncoder audioEncoder; // guarded by audioLock
    private boolean audioEncoderFailed = false; // guarded by audioLock

    static final class Subscriber {
        volatile ServerPlayer player;
        final boolean local;

        Subscriber(ServerPlayer player, boolean local) {
            this.player = player;
            this.local = local;
        }
    }

    StreamedScreen(ScreenKey key, int streamId, String url, int width, int height) {
        this.key = key;
        this.streamId = streamId;
        this.url = url;
        this.browserW = width;
        this.browserH = height;
    }

    public ServerScreenBrowser getBrowser() {
        return browser;
    }

    /** Creates the CEF browser if possible. Retried from the manager tick until CEF is ready. */
    void ensureBrowser() {
        if (closed || browser != null || browserRequested || !ServerCefManager.isReady())
            return;

        browserRequested = true;
        ServerCefManager.submit(() -> {
            try {
                CefClient client = ServerCefManager.getCefClient();
                if (client == null || closed) {
                    browserRequested = false;
                    return;
                }

                // Integrated servers reuse MCEF's client; make sure popup
                // (target=_blank) redirection is installed there too.
                ServerCefManager.installPopupRedirect(client);

                // Recreated browsers must recover automatically too
                ServerCefManager.installCrashRecovery(client);

                ServerScreenBrowser b = new ServerScreenBrowser(client, url, browserW, browserH,
                        ServerCefManager.createRequestContext());
                b.setCloseAllowed();
                b.createImmediately();
                browser = b;
                browserCreatedMs = System.currentTimeMillis();
                lastInputMs = 0;
                keyframeNeeded = true;
                Log.info("Created server-side browser for screen %s (%dx%d)", key, browserW, browserH);

                if (closed) // closed while we were creating it
                    b.closeBrowser();
            } catch (Throwable t) {
                Log.errorEx("Failed to create server-side browser for " + key, t);
            }
        });
    }

    void startEncoding() {
        if (encodeStarted)
            return;
        encodeStarted = true;

        int fps = Math.max(1, CommonConfig.Stream.streamFps);
        encodePeriodNs = 1_000_000_000L / fps;
        encodeTask = ServerBrowserManager.encodePool().schedule(this::runEncodeTick, 0, TimeUnit.NANOSECONDS);
    }

    /**
     * Runs one encode tick and re-schedules itself.
     *
     * The encode cadence follows the browser's own paint clock: Chromium
     * paints at ~30 Hz on its own timer, and sampling that with a second,
     * unsynchronized 30 Hz timer produces a beat pattern (ticks that see no
     * new frame followed by ticks that skip one) - visible as periodic
     * judder. Instead, each new paint is encoded within a few milliseconds
     * of landing, so the encoded frame spacing matches the paint spacing.
     */
    private void runEncodeTick() {
        int status = encodeTick(); // never throws

        if (closed || encodeBroken)
            return;

        long delay = switch (status) {
            // Sleep until the pacing deadline, then poll fast for the next paint
            case TICK_ENCODED -> Math.max(POLL_NS, nextAllowedNs - PACE_SLACK_NS - System.nanoTime());
            case TICK_WAITING -> POLL_NS;
            default -> encodePeriodNs;
        };
        encodeTask = ServerBrowserManager.encodePool().schedule(this::runEncodeTick, delay, TimeUnit.NANOSECONDS);
    }

    void close() {
        closed = true;

        if (encodeTask != null) {
            encodeTask.cancel(false);
            encodeTask = null;
        }

        ServerBrowserManager.encodePool().execute(() -> {
            synchronized (encoderLock) {
                if (encoder != null) {
                    encoder.close();
                    encoder = null;
                }
            }
        });

        ServerScreenBrowser b = browser;
        browser = null;
        if (b != null)
            ServerCefManager.submit(b::closeBrowser);

        subscribers.clear();
        audioStopped();
    }

    // ------------------------------------------------------------------
    // Audio (called from the CEF audio thread via ServerAudioRouter)
    // ------------------------------------------------------------------

    /** True if the given CefBrowser is this screen's browser. */
    boolean ownsBrowser(org.cef.browser.CefBrowser other) {
        ServerScreenBrowser b = browser;
        return b != null && (b == other || b.getIdentifier() == other.getIdentifier());
    }

    public void setAudioFormat(int sampleRate, int channels) {
        audioRate = sampleRate > 0 ? sampleRate : 48000;
        audioChannels = Math.max(1, channels);
    }

    public void setAudioChannels(int channels) {
        if (channels > 0)
            audioChannels = channels;
    }

    /**
     * Pushes one PCM packet from CEF. With the current MCEF jcef natives the
     * data only contains the first channel (length == frames); the layout check
     * below also supports properly interleaved data should the natives improve.
     */
    public void pushAudio(float[] data, int frames) {
        if (closed || data == null || frames <= 0 || subscribers.isEmpty())
            return;

        boolean anyRemote = false, anyLocal = false;
        for (Subscriber sub : subscribers.values()) {
            if (sub.local) anyLocal = true;
            else anyRemote = true;
        }

        int ch = (data.length == frames * audioChannels) ? audioChannels : 1;
        float[] stereo = PcmConverter.toStereo48k(data, frames, ch, audioRate);

        // Single-JVM (integrated server) viewers get the PCM directly, skipping Opus
        if (anyLocal)
            ScreenAudioManager.pushFromIntegratedServer(key, stereo);

        if (!anyRemote)
            return;

        List<byte[]> packets;
        synchronized (audioLock) {
            if (closed || audioEncoderFailed)
                return;

            if (audioEncoder == null) {
                if (!net.montoyo.wd.WebDisplays.isFfmpegReady()) {
                    if (net.montoyo.wd.WebDisplays.hasFfmpegFailed()) {
                        audioEncoderFailed = true;
                        Log.errorEx("Cannot encode screen audio: the FFmpeg natives failed to load (see startup log).", null);
                    }
                    return; // still loading: drop this packet, retry on the next one
                }

                try {
                    // 128 kbit/s stereo: transparent for music, still a rounding
                    // error next to the ~2.5 Mbit/s video budget.
                    audioEncoder = new OpusEncoder(128);
                } catch (Throwable t) {
                    audioEncoderFailed = true;
                    Log.errorEx("Failed to create Opus encoder; screen audio disabled for " + key, t);
                    return;
                }
            }

            packets = audioEncoder.encode(stereo);
        }

        for (byte[] packet : packets) {
            S2CMessageStreamAudio msg = new S2CMessageStreamAudio(key.pos(), key.side(), packet);

            for (Subscriber sub : subscribers.values()) {
                if (sub.local)
                    continue;

                ServerPlayer ply = sub.player;
                if (ply != null && !ply.hasDisconnected())
                    WDNetworkRegistry.INSTANCE.send(PacketDistributor.PLAYER.with(() -> ply), msg);
            }
        }
    }

    public void audioStopped() {
        synchronized (audioLock) {
            if (audioEncoder != null) {
                audioEncoder.close();
                audioEncoder = null;
            }
        }
    }

    // ------------------------------------------------------------------
    // Subscribers
    // ------------------------------------------------------------------

    void addSubscriber(ServerPlayer ply, boolean local) {
        subscribers.compute(ply.getUUID(), (uuid, old) -> {
            if (old != null && old.local == local) {
                old.player = ply;
                return old;
            }
            return new Subscriber(ply, local);
        });
        lastViewerTime = System.currentTimeMillis();
        keyframeNeeded = true;
    }

    void removeSubscriber(UUID uuid) {
        subscribers.remove(uuid);
    }

    boolean hasSubscribers() {
        return !subscribers.isEmpty();
    }

    ConcurrentHashMap<UUID, Subscriber> subscribers() {
        return subscribers;
    }

    void requestKeyframe() {
        keyframeNeeded = true;
    }

    // ------------------------------------------------------------------
    // Browser liveness / crash recovery
    // ------------------------------------------------------------------

    /**
     * Called (from the CEF thread) when Chromium reports this screen's render
     * process gone. Reload recreates the render process while keeping the
     * browser and its request context (cookies/logins survive); if that does
     * not bring paints back, the watchdog below falls back to a full recreate.
     */
    void onRenderProcessDead(String status) {
        ServerScreenBrowser b = browser;
        if (closed || b == null)
            return;

        Log.warning("Screen %s: the browser render process died (%s); reloading the page", key, status);
        lastInputMs = 0;
        keyframeNeeded = true;
        ServerCefManager.submit(() -> {
            try {
                b.reload();
            } catch (Throwable t) {
                Log.warningEx("Screen " + key + ": reload after render process death failed", t);
            }
        });
    }

    /**
     * Periodic health check (server thread, every ~2s from the manager tick).
     *
     * A dead render process is invisible from the outside: the browser object
     * keeps accepting input and simply never paints again. So whenever paints
     * stop under circumstances where a live renderer would paint - right after
     * player input, or at all for a minute with viewers present - ask CEF to
     * repaint the view (a live renderer always answers within milliseconds,
     * static page or not). No answer means the renderer is dead or hung:
     * recreate the browser at the current URL.
     */
    void checkBrowserHealth(long now) {
        ServerScreenBrowser b = browser;
        if (closed || b == null || !hasSubscribers() || browserCreatedMs == 0) {
            probeSentMs = 0;
            return;
        }

        long lastPaint = Math.max(b.getLastPaintMs(), browserCreatedMs);

        if (probeSentMs == 0) {
            long input = lastInputMs;
            boolean inputIgnored = input != 0 && input > lastPaint && now - input >= INPUT_SILENCE_MS;
            boolean longSilence = now - lastPaint >= IDLE_PROBE_MS;
            if (inputIgnored || longSilence) {
                probeSentMs = now;
                ServerCefManager.submit(() -> {
                    try {
                        b.requestRepaint();
                    } catch (Throwable ignored) {
                    }
                });
            }
            return;
        }

        if (b.getLastPaintMs() >= probeSentMs) {
            probeSentMs = 0; // probe answered: the renderer is alive
            return;
        }

        if (now - probeSentMs >= PROBE_TIMEOUT_MS) {
            probeSentMs = 0;
            if (now - lastRecoveryMs >= RECOVERY_COOLDOWN_MS) {
                lastRecoveryMs = now;
                Log.warning("Screen %s: the browser stopped painting and ignored a repaint probe "
                        + "(render process dead or hung); recreating the browser at %s", key, url);
                recreateBrowser();
            }
        }
    }

    /** Closes the (broken) browser and creates a fresh one at the current URL. */
    private void recreateBrowser() {
        ServerScreenBrowser old = browser;
        browser = null;
        browserRequested = false;
        browserCreatedMs = 0;
        lastInputMs = 0;
        keyframeNeeded = true;
        if (old != null)
            ServerCefManager.submit(old::closeBrowser);
        ensureBrowser();
    }

    /** Keeps {@link #url} tracking in-browser navigation so recovery reloads the page the viewers were on. */
    void noteUrl(String url) {
        this.url = url;
    }

    /**
     * Delivery report from one viewer (server thread). Multiplicative
     * decrease on trouble, slow additive-ish recovery after a sustained
     * clean period - classic AIMD, so the stream settles just below the
     * bottleneck (network or otherwise) instead of oscillating.
     */
    void onClientFeedback(ServerPlayer ply, int framesReceived, int videoStalls, int audioGlitches) {
        long now = System.currentTimeMillis();

        // Stall reports only count while we are actually sending fast video;
        // a page painting sporadically (e.g. a 5 fps animation) produces
        // large arrival gaps that are perfectly normal.
        boolean sendingFastVideo = now - lastSentWallMs < 1500
                && sendIntervalEmaMs > 0 && sendIntervalEmaMs < 50; // > ~20 fps
        boolean videoBad = sendingFastVideo && videoStalls >= 3;
        boolean audioBad = audioGlitches >= 2;

        if (videoBad || audioBad) {
            lastNetBadMs = now;
            if (now - lastNetChangeMs >= 4000 && netScale > 0.15) {
                netScale = Math.max(0.15, netScale * 0.7);
                lastNetChangeMs = now;
                Log.info("Screen %s: viewer %s reports starved delivery (frames=%d, stalls=%d, audio glitches=%d); lowering stream quality (net scale %.2f)",
                        key, ply.getName().getString(), framesReceived, videoStalls, audioGlitches, netScale);
            }
        } else if (netScale < 1.0
                && now - lastNetBadMs >= 20_000
                && now - lastNetChangeMs >= 10_000) {
            netScale = Math.min(1.0, netScale * 1.15);
            lastNetChangeMs = now;
            Log.info("Screen %s: delivery clean for 20s; raising stream quality (net scale %.2f)", key, netScale);
        }
    }

    // ------------------------------------------------------------------
    // Browser control (server thread)
    // ------------------------------------------------------------------

    void loadUrl(String url) {
        this.url = url;
        ServerScreenBrowser b = browser;
        if (b != null) {
            ServerCefManager.submit(() -> b.loadURL(url));
            keyframeNeeded = true;
        }
    }

    void resize(int width, int height) {
        browserW = width;
        browserH = height;
        ServerScreenBrowser b = browser;
        if (b != null) {
            ServerCefManager.submit(() -> b.resize(width, height));
            keyframeNeeded = true;
        }
    }

    /**
     * Injects a mouse event; port of the old client-side
     * ScreenBlockEntity.handleMouseEvent (including its button swap).
     */
    void injectMouse(ClickControl.ControlType event, Vector2i vec, int button) {
        if (button > 1)
            return; // buttons above 1 used to crash the game

        ServerScreenBrowser b = browser;
        if (b == null)
            return;

        if (button == 1) button = 0;
        else if (button == 0) button = 1;

        final int btn = button;
        final int lastX = lastMousePos.x;
        final int lastY = lastMousePos.y;

        if (event != ClickControl.ControlType.MOVE)
            lastInputMs = System.currentTimeMillis();

        ServerCefManager.submit(() -> {
            if (event == ClickControl.ControlType.CLICK) {
                b.sendMouseMove(vec.x, vec.y);
                b.sendMousePress(vec.x, vec.y, btn);
                b.sendMouseRelease(vec.x, vec.y, btn);
            } else if (event == ClickControl.ControlType.DOWN) {
                b.sendMouseMove(vec.x, vec.y);
                b.sendMousePress(vec.x, vec.y, btn);
            } else if (event == ClickControl.ControlType.MOVE) {
                b.sendMouseMove(vec.x, vec.y);
            } else if (event == ClickControl.ControlType.UP) {
                b.sendMouseRelease(lastX, lastY, btn);
            }

            b.setFocus(true);
        });

        if (vec != null) {
            lastMousePos.x = vec.x;
            lastMousePos.y = vec.y;
        }
    }

    /**
     * Injects typed keys; port of the old client-side ScreenBlockEntity.type.
     */
    void injectType(String text, com.google.gson.Gson gson) {
        ServerScreenBrowser b = browser;
        if (b == null)
            return;

        lastInputMs = System.currentTimeMillis();
        ServerCefManager.submit(() -> {
            try {
                if (text.startsWith("t")) {
                    for (int i = 1; i < text.length(); i++) {
                        char chr = text.charAt(i);
                        if (chr == 1)
                            break;

                        b.sendKeyTyped(chr, 0);
                    }
                } else {
                    TypeData[] data = gson.fromJson(text, TypeData[].class);

                    for (TypeData ev : data) {
                        if (ev.getKeyCode() == 257) {
                            ev = new TypeData(ev.getAction(), 10, ev.getModifier(), ev.getScanCode());
                        }

                        switch (ev.getAction()) {
                            case PRESS -> {
                                b.sendKeyPress(ev.getKeyCode(), ev.getScanCode(), ev.getModifier());
                                if (ev.getKeyCode() == 10)
                                    b.sendKeyTyped('\r', ev.getModifier());
                            }
                            case RELEASE -> b.sendKeyRelease(ev.getKeyCode(), ev.getScanCode(), ev.getModifier());
                            case TYPE -> b.sendKeyTyped((char) ev.getKeyCode(), ev.getModifier());
                            default -> throw new RuntimeException("Invalid type action '" + ev.getAction() + '\'');
                        }
                    }
                }

                b.setFocus(true);
            } catch (Throwable t) {
                Log.warningEx("Suspicious keyboard type packet received...", t);
            }
        });
    }

    // ------------------------------------------------------------------
    // Encoding (encoder pool thread)
    // ------------------------------------------------------------------

    private int encodeTick() {
        try {
            if (closed)
                return TICK_IDLE;

            ServerScreenBrowser b = browser;
            if (b == null)
                return TICK_IDLE;

            boolean hasRemote = false;
            for (Subscriber sub : subscribers.values()) {
                if (!sub.local) {
                    hasRemote = true;
                    break;
                }
            }

            if (!hasRemote)
                return TICK_IDLE; // local viewers read frames directly from the browser

            boolean forceKey = keyframeNeeded;
            long now = System.currentTimeMillis();
            long nowNs = System.nanoTime();

            // A new paint landed but the pacing budget is spent (Chromium
            // painting faster than the stream fps): wait it out.
            boolean hasNew = b.getFrameCounter() != grabCounter;
            if (hasNew && !forceKey && nowNs < nextAllowedNs - PACE_SLACK_NS)
                return TICK_WAITING;

            ServerScreenBrowser.FrameCopy copy = hasNew ? b.copyFrame(grab, grabCounter) : null;
            if (copy != null) {
                grab = copy;
                grabCounter = copy.counter;
                lastContentChangeMs = now;
            } else {
                if (grab == null || grabCounter == 0)
                    return TICK_IDLE; // nothing captured yet

                // No new frame: re-encode the last one if a keyframe was
                // requested, or while inside the refinement window (lets the
                // encoder polish the initial coarse keyframe of static pages).
                if (!forceKey && now - lastContentChangeMs >= REFINE_WINDOW_MS)
                    return TICK_IDLE;
                if (!forceKey && nowNs - lastEncodeNs < encodePeriodNs)
                    return TICK_WAITING; // refine at the normal frame cadence
                copy = grab;
            }

            if (forceKey)
                lastContentChangeMs = now; // refine after keyframes too (new viewers)

            List<VideoEncoder.EncodedFrame> frames;
            byte codecId;
            double encMs = 0;
            synchronized (encoderLock) {
                if (closed)
                    return TICK_IDLE;

                // FFmpeg natives still loading: try again on the next tick.
                // (Everything below, including the auto-quality benchmark,
                // needs them.)
                if (encoder == null && !net.montoyo.wd.WebDisplays.isFfmpegReady()) {
                    if (net.montoyo.wd.WebDisplays.hasFfmpegFailed())
                        throw new IllegalStateException("The FFmpeg natives failed to load (see startup log)");
                    keyframeNeeded = forceKey;
                    return TICK_IDLE;
                }

                // Encode at a capped resolution: the browser stays at full size
                // (clicks/layout unaffected) but streaming e.g. 1920x1066 in
                // full costs several times the CPU and bitrate of 720p for
                // barely any visible gain on an in-world screen. The cap is on
                // total pixels, so it applies to any aspect ratio.
                StreamCodec codec = StreamCodec.pickEncoder();
                int fps = Math.max(1, CommonConfig.Stream.streamFps);
                int outW = Math.max(2, copy.width & ~1);
                int outH = Math.max(2, copy.height & ~1);
                // Start from the benchmark-based cap, scaled by the measured
                // runtime headroom (cpuScale may exceed 1: the cold-start
                // benchmark is often several times slower than reality) and
                // by the network feedback scale, always inside the
                // 360p..720p / bitrate hard bounds. When the network scale
                // pushes below the 360p floor, the leftover is applied to the
                // bitrate instead: resolution stops at 360p but the stream
                // keeps shrinking until the viewer's link can carry it.
                long hardCap = StreamQuality.hardMaxPixels(fps, codec);
                long cpuCapPx = Math.min(hardCap, (long) (StreamQuality.maxPixels(fps, codec) * cpuScale));
                lastCpuCapPixels = cpuCapPx;
                long desired = (long) (cpuCapPx * netScale);
                long maxPixels = Math.max(640 * 360, desired);
                double bitrateScale = desired < maxPixels ? Math.max(0.35, (double) desired / maxPixels) : 1.0;
                lastHardCapPixels = hardCap;
                long pixels = (long) outW * outH;
                if (pixels > maxPixels) {
                    double s = Math.sqrt((double) maxPixels / pixels);
                    outW = Math.max(2, ((int) (outW * s)) & ~1);
                    outH = Math.max(2, ((int) (outH * s)) & ~1);
                }
                lastOutPixels = (long) outW * outH;

                // Bitrate follows the actual stream size (a 360p stream must
                // not burn the 720p bandwidth budget), further reduced by the
                // network scale leftover once the resolution floor is hit.
                int kbps = Math.max(300,
                        (int) (StreamQuality.bitrateKbpsFor((long) outW * outH, fps, codec) * bitrateScale));

                if (encoder != null && (encoder.getWidth() != outW || encoder.getHeight() != outH || encoderKbps != kbps)) {
                    encoder.close();
                    encoder = null;
                }

                if (encoder == null) {
                    encoder = new VideoEncoder(codec, outW, outH, fps, kbps);
                    encoderKbps = kbps;
                    forceKey = true;
                    Log.info("Screen %s: %s encoder started (%dx%d browser -> %dx%d stream @ %d kbps)",
                            key, codec, copy.width, copy.height, outW, outH, kbps);
                }

                codecId = encoder.getCodec().wireId;
                keyframeNeeded = false;
                lastEncodeNs = nowNs;
                // Advance the pacing bucket; the max() forgives idle gaps so a
                // static page doesn't bank tokens for a burst later.
                nextAllowedNs = Math.max(nextAllowedNs, nowNs - encodePeriodNs) + encodePeriodNs;
                long encT0 = System.nanoTime();
                frames = encoder.encode(copy.buffer, copy.width, copy.height, forceKey);
                encMs = (System.nanoTime() - encT0) / 1e6;
            }

            long sentBytes = 0;
            for (VideoEncoder.EncodedFrame frame : frames) {
                sendFrame(frame, codecId);
                sentBytes += frame.data.length;
            }

            noteEncodeStats(now, encMs, frames.size(), sentBytes);
            return TICK_ENCODED;
        } catch (Throwable t) {
            // Don't retry (and spam the log) every tick: a broken encoder stays broken.
            Log.errorEx("Error while encoding screen stream " + key
                    + "; video for this screen is disabled until it is reopened", t);
            synchronized (encoderLock) {
                if (encoder != null) {
                    encoder.close();
                    encoder = null;
                }
            }
            encodeBroken = true; // runEncodeTick stops re-scheduling
            return TICK_IDLE;
        }
    }

    /**
     * Tracks real per-frame encode cost and scales the stream resolution so
     * encoding never becomes the bottleneck: if a frame takes longer than its
     * share of the frame period, the effective fps drops below the target and
     * playback turns jerky no matter what the client does.
     */
    private void noteEncodeStats(long nowMs, double encMs, int frameCount, long bytes) {
        encodeEmaMs = encodeSamples == 0 ? encMs : encodeEmaMs * 0.95 + encMs * 0.05;
        encodeSamples++;

        // Send cadence, used by the network feedback to tell "frames arrive
        // in stalls because the link is congested" apart from "the page just
        // paints rarely".
        if (prevSentWallMs != 0) {
            long interval = Math.min(2000, nowMs - prevSentWallMs);
            sendIntervalEmaMs = sendIntervalEmaMs == 0 ? interval : sendIntervalEmaMs * 0.9 + interval * 0.1;
        }
        prevSentWallMs = nowMs;
        lastSentWallMs = nowMs;

        statFrames += frameCount;
        statBytes += bytes;
        statEncodeMs += encMs;
        if (statWindowStart == 0) {
            statWindowStart = nowMs;
        } else if (nowMs - statWindowStart >= 30_000) {
            double secs = (nowMs - statWindowStart) / 1000.0;
            double net = netScale;
            String scales = (cpuScale != 1.0 || net != 1.0)
                    ? String.format(" (scale: cpu %.2f, net %.2f)", cpuScale, net) : "";
            Log.info("Screen %s stream stats: %.1f fps sent, avg encode %.1f ms/frame, %.0f kbit/s%s",
                    key, statFrames / secs,
                    statFrames > 0 ? statEncodeMs / statFrames : 0.0,
                    statBytes * 8.0 / 1000.0 / secs, scales);
            statWindowStart = nowMs;
            statFrames = 0;
            statBytes = 0;
            statEncodeMs = 0;
        }

        maybeAdaptResolution(nowMs);
    }

    private void maybeAdaptResolution(long nowMs) {
        // Need a settled average (~2s of frames) and a cooldown between steps
        if (encodeSamples < 60 || nowMs - lastAdaptMs < 10_000)
            return;

        double periodMs = encodePeriodNs / 1e6;
        boolean changed = false;

        if (encodeEmaMs > periodMs * 0.6 && cpuScale > 0.25) {
            cpuScale = Math.max(0.25, cpuScale * 0.75);
            Log.info("Screen %s: encoding too slow (%.1f ms/frame vs %.1f ms budget); lowering stream resolution (scale %.2f)",
                    key, encodeEmaMs, periodMs, cpuScale);
            changed = true;
        } else if (encodeEmaMs < periodMs * 0.2 && cpuScale < 8.0 && lastCpuCapPixels < lastHardCapPixels) {
            // Plenty of measured headroom and the CPU cap (not the network
            // scale or the 720p/bitrate bound) is what limits the stream:
            // grow, even past the (cold, pessimistic) startup benchmark.
            // When the network feedback is what holds the stream down,
            // raising the CPU cap would change nothing and only force
            // pointless encoder rebuilds (and keyframes).
            cpuScale = Math.min(8.0, cpuScale * 1.3);
            Log.info("Screen %s: encoder has headroom (%.1f ms/frame vs %.1f ms budget); raising stream resolution (scale %.2f)",
                    key, encodeEmaMs, periodMs, cpuScale);
            changed = true;
        }

        if (changed) {
            lastAdaptMs = nowMs;
            encodeSamples = 0; // re-measure at the new size
            synchronized (encoderLock) {
                if (encoder != null) {
                    encoder.close();
                    encoder = null; // recreated (with a keyframe) on the next tick
                }
            }
        }
    }

    private void sendFrame(VideoEncoder.EncodedFrame frame, byte codecId) {
        int total = frame.data.length;
        int chunkCount = (total + S2CMessageStreamFrame.MAX_CHUNK_SIZE - 1) / S2CMessageStreamFrame.MAX_CHUNK_SIZE;
        if (chunkCount > 255) {
            Log.warning("Encoded frame for %s is too large (%d bytes), dropping", key, total);
            return;
        }

        if (!firstFrameLogged) {
            firstFrameLogged = true;
            Log.info("Screen %s: first video frame encoded and sent (%d bytes, keyframe=%b, streamId=%d)",
                    key, total, frame.keyframe, streamId);
        }

        seq++;

        for (int i = 0; i < chunkCount; i++) {
            int off = i * S2CMessageStreamFrame.MAX_CHUNK_SIZE;
            int len = Math.min(S2CMessageStreamFrame.MAX_CHUNK_SIZE, total - off);
            byte[] chunk = new byte[len];
            System.arraycopy(frame.data, off, chunk, 0, len);

            S2CMessageStreamFrame msg = new S2CMessageStreamFrame(
                    key.pos(), key.side(), streamId, seq, frame.keyframe, codecId, (byte) i, (byte) chunkCount, chunk);

            for (Subscriber sub : subscribers.values()) {
                if (sub.local)
                    continue;

                ServerPlayer ply = sub.player;
                if (ply != null && !ply.hasDisconnected())
                    WDNetworkRegistry.INSTANCE.send(PacketDistributor.PLAYER.with(() -> ply), msg);
            }
        }
    }
}
