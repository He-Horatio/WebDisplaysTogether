package net.montoyo.wd.serverbrowser;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFPlatform;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.config.CommonConfig;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.serialization.Util;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefRequestContext;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * Owns the CEF context used for server-side (world) browsers.
 *
 * - Dedicated server: picks up the java-cef natives from mods/mcef-libraries
 *   (the same builds MCEF uses), initializes a headless CefApp and pumps its
 *   message loop on a dedicated thread. The natives are not shipped in the jar
 *   and this class never downloads them: unless a build bundles the archive,
 *   the server owner installs them by hand.
 * - Integrated server (single-player / LAN): reuses the client's MCEF CefApp/CefClient,
 *   whose message loop is already pumped on the render thread. Only one CefApp may
 *   exist per JVM, so this is mandatory. Nothing has to be installed: MCEF already
 *   provides the runtime on a client.
 */
public final class ServerCefManager {
    private ServerCefManager() {
    }

    private static final AtomicBoolean startedInit = new AtomicBoolean(false);
    private static final AtomicBoolean popupRedirectInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean crashRecoveryInstalled = new AtomicBoolean(false);
    private static volatile boolean ready = false;
    private static volatile boolean failed = false;

    // Dedicated-server-only state
    private static volatile CefApp cefApp;
    private static volatile CefClient cefClient;
    private static volatile Thread cefThread;
    private static volatile Process xvfbProcess;
    private static final LinkedBlockingQueue<Runnable> cefTasks = new LinkedBlockingQueue<>();
    private static volatile boolean running = false;

    public static boolean isDedicated() {
        return FMLEnvironment.dist != Dist.CLIENT;
    }

    /** True once a CefClient is available for creating server-side browsers. */
    public static boolean isReady() {
        if (isDedicated())
            return ready;
        else
            return MCEF.isInitialized();
    }

    public static boolean hasFailed() {
        return failed;
    }

    /**
     * The CefClient used for server-side browsers. On integrated servers this is
     * MCEF's client (shared with minepads); on dedicated servers it is our own.
     */
    public static CefClient getCefClient() {
        if (isDedicated())
            return cefClient;
        else
            return MCEF.isInitialized() ? MCEF.getClient().getHandle() : null;
    }

    /**
     * Request context for server-side browsers. When incognito mode is enabled
     * (default), every screen browser gets its OWN in-memory context: cookies,
     * logins and storage are isolated per screen, never written to disk, and
     * wiped when the browser closes. Must be called with CEF ready, from the
     * CEF thread (dedicated) or any thread (integrated).
     */
    public static CefRequestContext createRequestContext() {
        if (!CommonConfig.Stream.incognito)
            return null; // use the global (persistent) context, shared between screens

        return CefRequestContext.createContext(null);
    }

    /**
     * Popup redirection for screen browsers. Many sites open links in a new
     * tab/window (target="_blank" - e.g. every video card on bilibili or
     * youtube search results). Screens have nowhere to show a popup and the
     * java-cef natives cancel ALL popups for off-screen browsers before the
     * Java CefLifeSpanHandler is even consulted - such clicks silently do
     * NOTHING. Instead, patch every loaded page so that window.open and
     * target="_blank" anchors navigate the SAME view.
     *
     * Installed once per JVM: on our own CefClient on dedicated servers, and
     * through MCEF's handler multiplexer on integrated (single-player)
     * servers - where the findByBrowser check keeps normal client browsers
     * (minepads) unaffected.
     */
    private static final String POPUP_PATCH_JS =
            "(function(){"
            + "if (window.__wdtPopupPatch) return; window.__wdtPopupPatch = true;"
            + "window.open = function(u){ if (u) window.location.href = u; return null; };"
            + "document.addEventListener('click', function(e){"
            +   "var el = e.target;"
            +   "while (el && el.tagName !== 'A') el = el.parentElement;"
            +   "if (!el || !el.href) return;"
            +   "var t = el.getAttribute('target');"
            +   "if (t && t !== '_self' && t !== '_parent' && t !== '_top') {"
            +     "e.preventDefault(); e.stopPropagation();"
            +     "window.location.href = el.href;"
            +   "}"
            + "}, true);"
            + "})();";

    public static void installPopupRedirect(CefClient client) {
        if (client == null || !popupRedirectInstalled.compareAndSet(false, true))
            return;

        org.cef.handler.CefLoadHandlerAdapter patcher = new org.cef.handler.CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame,
                                    org.cef.network.CefRequest.TransitionType transitionType) {
                patch(browser, frame);
            }

            @Override
            public void onLoadEnd(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, int httpStatusCode) {
                patch(browser, frame); // late but reliable; the patch itself is idempotent
            }

            private void patch(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame) {
                if (frame == null || !frame.isMain())
                    return;
                if (!isDedicated() && ServerBrowserManager.findByBrowser(browser) == null)
                    return; // shared MCEF client: not a screen browser

                browser.executeJavaScript(POPUP_PATCH_JS, frame.getURL(), 0);
            }
        };

        if (isDedicated())
            client.addLoadHandler(patcher);
        else
            MCEF.getClient().addLoadHandler(patcher); // raw client already has MCEF's multiplexer registered
    }

    /**
     * Automatic recovery from render process crashes. Chromium render
     * processes do die on long-running heavy pages (usually OOM); without
     * this handler the CefBrowser object lives on, silently swallowing input
     * and never painting again - the screen freezes and stops reacting to
     * the mouse until a player rebuilds it. Reloading recreates the render
     * process (cookies/logins survive: they live in the browser process).
     *
     * Installed once per JVM on the raw CefClient. The findByBrowser check
     * limits the reaction to screen browsers; for everything else (e.g.
     * minepads on the shared integrated-server client) the adapter defaults
     * match having no request handler at all.
     */
    public static void installCrashRecovery(CefClient client) {
        if (client == null || !crashRecoveryInstalled.compareAndSet(false, true))
            return;

        client.addRequestHandler(new org.cef.handler.CefRequestHandlerAdapter() {
            @Override
            public void onRenderProcessTerminated(org.cef.browser.CefBrowser browser,
                                                  org.cef.handler.CefRequestHandler.TerminationStatus status) {
                StreamedScreen ss = ServerBrowserManager.findByBrowser(browser);
                if (ss != null)
                    ss.onRenderProcessDead(status != null ? status.name() : "unknown");
                else
                    Log.warning("A render process died (%s) for a browser not owned by any screen.",
                            status != null ? status.name() : "unknown");
            }
        });
    }

    /**
     * Kicks off asynchronous CEF bootstrap on the dedicated server.
     * Safe to call multiple times; only the first call has an effect.
     */
    public static void initDedicated() {
        if (!isDedicated() || !startedInit.compareAndSet(false, true))
            return;

        Thread th = new Thread(ServerCefManager::dedicatedThreadMain, "WDT-CEF");
        th.setDaemon(true);
        cefThread = th;
        running = true;
        th.start();
    }

    /** Runs a task on the CEF thread (dedicated) or immediately (integrated: any thread works, JCEF proxies natives). */
    public static void submit(Runnable task) {
        if (isDedicated() && Thread.currentThread() != cefThread) {
            cefTasks.offer(task);
        } else {
            task.run();
        }
    }

    /** Blocking variant of {@link #submit}. */
    public static void submitAndWait(Runnable task) {
        if (isDedicated() && Thread.currentThread() != cefThread) {
            CountDownLatch latch = new CountDownLatch(1);
            cefTasks.offer(() -> {
                try {
                    task.run();
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            task.run();
        }
    }

    public static void shutdown() {
        if (!isDedicated())
            return; // client MCEF owns the shared context

        running = false;
        Thread th = cefThread;
        if (th != null) {
            try {
                th.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------
    // Dedicated server bootstrap + message pump
    // ------------------------------------------------------------------

    private static void dedicatedThreadMain() {
        try {
            bootstrapDedicated();
            ready = true;
            Log.info("Server-side CEF is ready.");
        } catch (SetupRequiredException t) {
            // Not an error: the server keeps running, screens just stay off.
            failed = true;
            Log.warning("Screens are disabled - %s", t.getMessage());
            return;
        } catch (Throwable t) {
            failed = true;
            Log.errorEx("Failed to initialize server-side CEF. Screens will stay black.", t);
            return;
        }

        // Optional self test (-Dwdt.cefSelfTest=true): renders a page in a
        // headless browser and reports whether real content was produced.
        // Useful for validating a server setup without connecting a client.
        if (Boolean.getBoolean("wdt.cefSelfTest"))
            startSelfTest();

        // Message pump + task loop
        while (running) {
            try {
                Runnable task;
                while ((task = cefTasks.poll()) != null)
                    task.run();

                cefApp.N_DoMessageLoopWork();
                Thread.sleep(5);
            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                Log.errorEx("Exception in CEF message loop", t);
            }
        }

        try {
            if (cefClient != null)
                cefClient.dispose();
            if (cefApp != null)
                cefApp.dispose();
        } catch (Throwable t) {
            Log.warningEx("Exception while shutting down CEF", t);
        }

        stopPrivateXvfb();
    }

    // ------------------------------------------------------------------
    // Self test
    // ------------------------------------------------------------------

    /**
     * Creates a real ServerScreenBrowser, loads a test page and logs whether
     * non-black frames were painted. Runs asynchronously; the result appears in
     * the log within ~30 seconds. Enabled with -Dwdt.cefSelfTest=true.
     */
    private static void startSelfTest() {
        Log.info("CEF self test: opening https://example.com/ in a headless browser...");

        final ServerScreenBrowser[] holder = new ServerScreenBrowser[1];
        submit(() -> {
            try {
                ServerScreenBrowser b = new ServerScreenBrowser(cefClient, "https://example.com/", 640, 480, createRequestContext());
                b.setCloseAllowed();
                b.createImmediately();
                holder[0] = b;
            } catch (Throwable t) {
                Log.errorEx("CEF self test: failed to create the test browser", t);
            }
        });

        Thread watcher = new Thread(() -> {
            try {
                long deadline = System.currentTimeMillis() + 30000;
                boolean content = false;
                long frames = 0;
                ServerScreenBrowser.FrameCopy copy = null;

                while (System.currentTimeMillis() < deadline && !content) {
                    Thread.sleep(1000);
                    ServerScreenBrowser b = holder[0];
                    if (b == null)
                        continue;

                    frames = b.getFrameCounter();
                    ServerScreenBrowser.FrameCopy c = b.copyFrame(copy, 0);
                    if (c == null)
                        continue;
                    copy = c;

                    int size = c.width * c.height * 4;
                    for (int i = 0; i < size; i += 4) {
                        if ((c.buffer.get(i) & 0xFF) > 16 || (c.buffer.get(i + 1) & 0xFF) > 16 || (c.buffer.get(i + 2) & 0xFF) > 16) {
                            content = true;
                            break;
                        }
                    }
                }

                if (content) {
                    Log.info("CEF self test PASSED: %d frame(s) painted with real page content. Server-side browsing works.", frames);
                    selfTestVideoPipeline(copy);
                } else if (frames > 0)
                    Log.warning("CEF self test FAILED: %d frame(s) painted but all pixels are black. The X display is likely broken.", frames);
                else
                    Log.warning("CEF self test FAILED: no frames painted within 30 seconds.");

                if (copy != null)
                    codecSelfTest(copy);

                ServerScreenBrowser b = holder[0];
                if (b != null) {
                    if (content)
                        selfTestInput(b, "640x480");
                    submit(b::closeBrowser);
                }

                // Repeat the input test on screen-sized browsers: input
                // handling in CEF can behave differently at large view sizes.
                if (content) {
                    int[][] sizes = {{1920, 1066}};
                    for (int[] size : sizes) {
                        int w = size[0], h = size[1];
                        ServerScreenBrowser big = selfTestCreateBrowser(w, h);
                        if (big != null) {
                            selfTestInput(big, w + "x" + h);
                            submit(big::closeBrowser);
                        }
                    }

                    // Jittered clicks: small screen (usually below the drag
                    // threshold) vs. large screen (bug reproduction).
                    selfTestJitterClick(480, 352, 2);
                    selfTestJitterClick(1920, 1066, 8);

                    // Event-delivery probe on a real, complex page.
                    selfTestDeliveryProbe(640, 480);
                    selfTestDeliveryProbe(1920, 1066);

                    // Real-site navigation probe: click video cards on bilibili
                    // and check whether the view navigates (target=_blank flow).
                    selfTestBilibiliClicks(992, 608, new int[][]{{300, 400}, {496, 304}, {700, 450}});
                    selfTestBilibiliClicks(1920, 1066, new int[][]{{170, 230}, {300, 450}, {660, 450}, {960, 600}});
                }
            } catch (InterruptedException ignored) {
            }
        }, "WDT-CEF-SelfTest");
        watcher.setDaemon(true);
        watcher.start();
    }

    /**
     * Round-trips a captured frame through the real VP8 encoder + decoder, the
     * same code path used for streaming to remote players. Catches environment
     * problems (e.g. JavaCPP failing to extract FFmpeg natives under
     * ModLauncher) that CEF alone cannot reveal.
     */
    private static void codecSelfTest(ServerScreenBrowser.FrameCopy copy) {
        net.montoyo.wd.video.StreamCodec codec;
        try {
            codec = net.montoyo.wd.video.StreamCodec.pickEncoder();
        } catch (Throwable t) {
            Log.errorEx("Codec self test FAILED: could not probe the FFmpeg encoders.", t);
            return;
        }

        try (net.montoyo.wd.video.VideoEncoder enc =
                     new net.montoyo.wd.video.VideoEncoder(codec, copy.width, copy.height, 20, 3000);
             net.montoyo.wd.video.VideoDecoder dec = new net.montoyo.wd.video.VideoDecoder(codec)) {

            java.util.List<net.montoyo.wd.video.VideoEncoder.EncodedFrame> packets =
                    enc.encode(copy.buffer, copy.width, copy.height, true);

            int decoded = 0;
            for (net.montoyo.wd.video.VideoEncoder.EncodedFrame p : packets) {
                if (dec.decode(p.data) != null)
                    decoded++;
            }

            if (decoded > 0)
                Log.info("%s codec self test PASSED: %d packet(s) encoded and decoded. Video streaming works.", codec, packets.size());
            else
                Log.warning("%s codec self test FAILED: encoded %d packet(s) but decoded none.", codec, packets.size());
        } catch (Throwable t) {
            Log.errorEx(codec + " codec self test FAILED: the FFmpeg-based encoder is broken on this server. "
                    + "Remote players will see black screens even though the browser renders.", t);
        }
    }

    /**
     * Input injection self test: loads a page that turns from red to green on
     * mousedown, then injects a click through the exact same code path used for
     * player clicks (ServerScreenBrowser.sendMouseMove/Press/Release) and
     * verifies the page reacted. Proves that in-game clicks can drive the
     * server-side browser on this machine. Blocking; call from the watcher thread.
     */
    /** Creates a browser of the given size for self tests (blocking). */
    private static ServerScreenBrowser selfTestCreateBrowser(int w, int h) throws InterruptedException {
        ServerScreenBrowser[] holder = new ServerScreenBrowser[1];
        submit(() -> {
            try {
                ServerScreenBrowser b = new ServerScreenBrowser(cefClient, "about:blank", w, h, createRequestContext());
                b.setCloseAllowed();
                b.createImmediately();
                holder[0] = b;
            } catch (Throwable t) {
                Log.errorEx("CEF self test: failed to create a test browser", t);
            }
        });
        Thread.sleep(2000);
        return holder[0];
    }

    /**
     * Loads a real, complex page (bilibili) and verifies that an injected
     * click is actually DELIVERED to the page DOM: a capturing mousedown
     * listener replaces the whole document with a solid green page.
     */
    private static void selfTestDeliveryProbe(int w, int h) {
        String label = w + "x" + h;
        try {
            ServerScreenBrowser b = selfTestCreateBrowser(w, h);
            if (b == null)
                return;

            Log.info("Delivery probe (%s): loading bilibili...", label);
            submit(() -> b.loadURL("https://www.bilibili.com/"));
            Thread.sleep(12000); // let the page load and settle

            String probeJs = "document.addEventListener('mousedown', function(){"
                    + "document.documentElement.innerHTML='';"
                    + "document.documentElement.style.background='#00ff00';"
                    + "}, true);";
            submit(() -> b.executeJavaScript(probeJs, b.getURL(), 0));
            Thread.sleep(1000);

            submit(() -> {
                int cx = w / 2, cy = h / 2;
                b.sendMouseMove(cx, cy);
                b.sendMousePress(cx, cy, 0);
                b.sendMouseRelease(cx, cy, 0);
                b.setFocus(true);
            });

            if (waitForCenter(b, (r, g, bl) -> g > 180 && r < 100 && bl < 100, 10000))
                Log.info("Delivery probe (%s) PASSED: the injected click reached the page DOM.", label);
            else
                Log.warning("Delivery probe (%s) FAILED: the injected click never reached the page DOM.", label);

            submit(b::closeBrowser);
        } catch (Throwable t) {
            Log.errorEx("Delivery probe crashed", t);
        }
    }

    /**
     * Loads bilibili at the given size and clicks the given view coordinates,
     * reporting whether each click navigated the top frame (video cards are
     * target="_blank" links, so this exercises the popup-redirect patch on a
     * real site).
     */
    private static void selfTestBilibiliClicks(int w, int h, int[][] points) {
        String label = w + "x" + h;
        try {
            ServerScreenBrowser b = selfTestCreateBrowser(w, h);
            if (b == null)
                return;

            Log.info("Bilibili click probe (%s): loading bilibili...", label);
            submit(() -> b.loadURL("https://www.bilibili.com/"));
            Thread.sleep(15000);

            for (int[] p : points) {
                String before = b.getURL();
                submit(() -> {
                    b.sendMouseMove(p[0], p[1]);
                    b.sendMousePress(p[0], p[1], 0);
                    b.sendMouseRelease(p[0], p[1], 0);
                    b.setFocus(true);
                });

                String after = before;
                long deadline = System.currentTimeMillis() + 6000;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(500);
                    after = b.getURL();
                    if (after != null && !after.equals(before))
                        break;
                }

                if (after != null && !after.equals(before)) {
                    Log.info("Bilibili click probe (%s): click at (%d, %d) NAVIGATED -> %s", label, p[0], p[1], after);
                    submit(() -> b.loadURL("https://www.bilibili.com/"));
                    Thread.sleep(10000);
                } else {
                    Log.warning("Bilibili click probe (%s): click at (%d, %d) did nothing (url stays %s)", label, p[0], p[1], before);
                }
            }

            submit(b::closeBrowser);
        } catch (Throwable t) {
            Log.errorEx("Bilibili click probe crashed", t);
        }
    }

    /**
     * Reproduces the "large screen ignores clicks" bug: a click whose DOWN and
     * UP are a few pixels apart (crosshair jitter, amplified by big browser
     * resolutions) starts a link drag in Chromium and never produces a DOM
     * click. Expected result WITHOUT the click-slop fix: no navigation.
     */
    private static void selfTestJitterClick(int w, int h, int jitterPx) {
        String label = w + "x" + h + " jitter=" + jitterPx + "px";
        try {
            ServerScreenBrowser b = selfTestCreateBrowser(w, h);
            if (b == null)
                return;

            String html = "<html><body style='margin:0;background:#f00'>"
                    + "<a target='_blank' href='https://example.com/' "
                    + "style='position:fixed;left:0;top:0;right:0;bottom:0'></a></body></html>";
            String url = "data:text/html;base64,"
                    + java.util.Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
            submit(() -> b.loadURL(url));

            if (!waitForCenter(b, (r, g, bl) -> r > 150 && g < 80 && bl < 80, 15000)) {
                Log.warning("Jitter click test (%s) SKIPPED: test page never rendered.", label);
                submit(b::closeBrowser);
                return;
            }

            String before = b.getURL();
            int cx = w / 2, cy = h / 2;
            submit(() -> {
                // What the server receives from GuiKeyboard: DOWN, then MOVEs
                // from the per-tick cursor resend, then UP at the drifted pos.
                b.sendMouseMove(cx, cy);
                b.sendMousePress(cx, cy, 0);
                b.sendMouseMove(cx + jitterPx / 2, cy + jitterPx / 2);
                b.sendMouseMove(cx + jitterPx, cy + jitterPx);
                b.sendMouseRelease(cx + jitterPx, cy + jitterPx, 0);
                b.setFocus(true);
            });

            boolean navigated = false;
            long deadline = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500);
                String after = b.getURL();
                if (after != null && !after.equals(before)) {
                    navigated = true;
                    break;
                }
            }

            if (navigated)
                Log.info("Jitter click test (%s): click still NAVIGATED (drag threshold not hit).", label);
            else
                Log.warning("Jitter click test (%s): click was SWALLOWED by Chromium's link drag - large-screen bug reproduced.", label);

            submit(b::closeBrowser);
        } catch (Throwable t) {
            Log.errorEx("Jitter click test crashed", t);
        }
    }

    private static void selfTestInput(ServerScreenBrowser b, String label) {
        try {
            // The worst case in-game: a full click (press+release on the same
            // element) on a target="_blank" link - the popup must be redirected
            // into the same browser (like bilibili/youtube video links).
            // Chromium refuses _blank navigation to data: URLs, so the target
            // is example.com (already proven reachable by the render test).
            String html = "<html><body style='margin:0;background:#f00'>"
                    + "<a target='_blank' href='https://example.com/' "
                    + "style='position:fixed;left:0;top:0;right:0;bottom:0'></a></body></html>";
            String url = "data:text/html;base64,"
                    + java.util.Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));

            Log.info("Input self test (%s): loading a click-sensitive test page...", label);
            submit(() -> b.loadURL(url));

            if (!waitForCenter(b, (r, g, bl) -> r > 150 && g < 80 && bl < 80, 15000)) {
                Log.warning("Input self test (%s) SKIPPED: the test page never rendered (red frame not seen).", label);
                return;
            }

            // Same sequence StreamedScreen.injectMouse produces for a CLICK
            // (button already CEF-mapped: 0 = left).
            submit(() -> {
                ServerScreenBrowser.FrameCopy c = b.copyFrame(null, 0);
                int cx = (c != null ? c.width : 640) / 2;
                int cy = (c != null ? c.height : 480) / 2;
                b.sendMouseMove(cx, cy);
                b.sendMousePress(cx, cy, 0);
                b.sendMouseRelease(cx, cy, 0);
                b.setFocus(true);
            });

            // example.com is a near-white page: distinctly different from red
            if (waitForCenter(b, (r, g, bl) -> r > 150 && g > 150 && bl > 150, 15000))
                Log.info("Input self test (%s) PASSED: an injected click on a target=_blank link navigated the view. "
                        + "In-game clicks (including new-tab links) work on this machine.", label);
            else
                Log.warning("Input self test (%s) FAILED: the injected click did not navigate. "
                        + "Players will not be able to interact with screens.", label);
        } catch (Throwable t) {
            Log.errorEx("Input self test crashed", t);
        }
    }

    private interface PixelCheck {
        boolean test(int r, int g, int b);
    }

    /** Polls the browser's center pixel until it matches {@code check}. */
    private static boolean waitForCenter(ServerScreenBrowser b, PixelCheck check, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ServerScreenBrowser.FrameCopy copy = null;

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
            ServerScreenBrowser.FrameCopy c = b.copyFrame(copy, 0);
            if (c == null)
                continue;
            copy = c;

            int idx = ((c.height / 2) * c.width + c.width / 2) * 4; // BGRA
            int blue = c.buffer.get(idx) & 0xFF;
            int grn = c.buffer.get(idx + 1) & 0xFF;
            int red = c.buffer.get(idx + 2) & 0xFF;

            if (check.test(red, grn, blue))
                return true;
        }
        return false;
    }

    /**
     * Second half of the self test: encodes the captured browser frame with the
     * VP8 streaming encoder and decodes it back, exactly like a dedicated
     * server + remote client would. Catches missing/broken FFmpeg natives.
     */
    private static void selfTestVideoPipeline(ServerScreenBrowser.FrameCopy copy) {
        net.montoyo.wd.video.StreamCodec codec = net.montoyo.wd.video.StreamCodec.pickEncoder();
        try (net.montoyo.wd.video.VideoEncoder enc = new net.montoyo.wd.video.VideoEncoder(codec,
                copy.width, copy.height, Math.max(1, CommonConfig.Stream.streamFps), StreamQuality.bitrateKbps())) {

            java.util.List<net.montoyo.wd.video.VideoEncoder.EncodedFrame> frames =
                    enc.encode(copy.buffer, copy.width, copy.height, true);
            if (frames.isEmpty()) {
                Log.warning("Video self test FAILED: the %s encoder produced no packets.", codec);
                return;
            }

            int totalBytes = 0;
            for (net.montoyo.wd.video.VideoEncoder.EncodedFrame f : frames)
                totalBytes += f.data.length;

            net.montoyo.wd.video.VideoDecoder dec = new net.montoyo.wd.video.VideoDecoder(codec);
            try {
                net.montoyo.wd.video.VideoDecoder.DecodedFrame df = dec.decode(frames.get(0).data);
                if (df == null) {
                    Log.warning("Video self test FAILED: encoded keyframe did not decode.");
                    return;
                }

                boolean decodedContent = false;
                int size = df.width * df.height * 4;
                for (int i = 0; i < size; i += 4) {
                    if ((df.buffer.get(i) & 0xFF) > 16 || (df.buffer.get(i + 1) & 0xFF) > 16 || (df.buffer.get(i + 2) & 0xFF) > 16) {
                        decodedContent = true;
                        break;
                    }
                }

                if (decodedContent)
                    Log.info("Video self test PASSED: VP8 encode(%d bytes)+decode round trip OK (%dx%d). "
                            + "The full server->client streaming pipeline works on this machine.", totalBytes, df.width, df.height);
                else
                    Log.warning("Video self test FAILED: decoded frame is all black.");
            } finally {
                dec.close();
            }
        } catch (Throwable t) {
            Log.errorEx("Video self test FAILED: the VP8/FFmpeg streaming codec is broken on this server. "
                    + "Screens will stay black for remote players.", t);
        }
    }

    // ------------------------------------------------------------------
    // Private Xvfb (headless Linux servers without any X display)
    // ------------------------------------------------------------------

    /**
     * Starts a private Xvfb server on a free display and returns its display
     * string (e.g. ":93"). Throws with actionable instructions if Xvfb is not
     * installed or cannot be started.
     */
    private static String startPrivateXvfb() throws Exception {
        String xvfb = findExecutable("Xvfb");
        if (xvfb == null) {
            throw new IllegalStateException(
                    "This server has no X display and Xvfb is not installed. Server-side browsing needs one of the two.\n"
                    + "  - Install Xvfb: 'apt install xvfb' (Debian/Ubuntu) or 'dnf install xorg-x11-server-Xvfb' (Fedora/RHEL).\n"
                    + "    The mod will then start and manage Xvfb automatically - no further setup needed.\n"
                    + "  - Or launch the server with a display available, e.g. 'xvfb-run -a java -jar ...'.");
        }

        Exception lastError = null;
        for (int n = 90; n < 100; n++) {
            File socket = new File("/tmp/.X11-unix/X" + n);
            File lock = new File("/tmp/.X" + n + "-lock");
            if (socket.exists() || lock.exists())
                continue; // display number already in use

            try {
                // Xvfb normally enables X11 access control. The Java server process has no
                // Xauthority cookie, so CEF's renderer would connect-fail and only produce
                // black OSR frames. This display is private (-nolisten tcp) and randomly
                // allocated, therefore disabling access control is the safe practical choice.
                Process proc = new ProcessBuilder(xvfb, ":" + n, "-screen", "0", "1920x1080x24", "-ac", "-nolisten", "tcp")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();

                // Wait for the X socket to appear (Xvfb starts in well under a second).
                // On some systems (e.g. WSL, where /tmp/.X11-unix is a read-only mount)
                // the filesystem socket is never created even though Xvfb runs fine and
                // accepts connections through the Linux abstract socket namespace, so a
                // missing socket file is not treated as a failure by itself.
                for (int i = 0; i < 20 && !socket.exists() && proc.isAlive(); i++)
                    Thread.sleep(100);

                if (proc.isAlive()) {
                    if (!socket.exists())
                        Log.warning("Xvfb :%d did not create %s (read-only /tmp/.X11-unix?); "
                                + "assuming the abstract X socket works.", n, socket);

                    xvfbProcess = proc;
                    Runtime.getRuntime().addShutdownHook(new Thread(ServerCefManager::stopPrivateXvfb, "WDT-Xvfb-Cleanup"));
                    Log.info("Started private Xvfb on display :%d (pid %d); CEF will render there.", n, proc.pid());
                    return ":" + n;
                }

                proc.destroyForcibly();
                lastError = new IllegalStateException("Xvfb exited immediately on display :" + n);
            } catch (Exception e) {
                lastError = e;
            }
        }

        throw new IllegalStateException("Failed to start a private Xvfb server", lastError);
    }

    private static void stopPrivateXvfb() {
        Process proc = xvfbProcess;
        xvfbProcess = null;
        if (proc != null && proc.isAlive())
            proc.destroy();
    }

    /**
     * Cheap liveness check for a local X display (":N" / ":N.S"): the socket in
     * /tmp/.X11-unix must exist. Non-local displays (with a hostname) are assumed
     * alive since we cannot check them cheaply. Used to catch stale DISPLAY values
     * (common on VPS images) before CefInitialize hard-exits the process on them.
     */
    private static boolean isLocalDisplayAlive(String display) {
        if (!display.startsWith(":"))
            return true;

        String num = display.substring(1);
        int dot = num.indexOf('.');
        if (dot >= 0)
            num = num.substring(0, dot);

        try {
            return new File("/tmp/.X11-unix/X" + Integer.parseInt(num.trim())).exists();
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /**
     * Sets an environment variable in the real (native) process environment using
     * libc setenv via JNA, which Minecraft ships. Java's System.getenv snapshot is
     * unaffected, but native code (like CEF's XOpenDisplay) sees the new value.
     * Returns false if JNA is unavailable; callers must have a fallback (we always
     * also pass --display to Chromium).
     */
    private static boolean setNativeEnv(String name, String value) {
        try {
            Class<?> nativeLibrary = Class.forName("com.sun.jna.NativeLibrary");
            Object libc = nativeLibrary.getMethod("getInstance", String.class).invoke(null, "c");
            Object setenv = nativeLibrary.getMethod("getFunction", String.class).invoke(libc, "setenv");
            Class.forName("com.sun.jna.Function")
                    .getMethod("invokeInt", Object[].class)
                    .invoke(setenv, (Object) new Object[]{name, value, 1});
            return true;
        } catch (Throwable t) {
            Log.warning("Could not set %s=%s natively (JNA unavailable?): %s", name, value, t.toString());
            return false;
        }
    }

    /** Looks up an executable on PATH plus the usual system locations. */
    private static String findExecutable(String name) {
        ArrayList<String> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null)
            dirs.addAll(Arrays.asList(path.split(File.pathSeparator)));
        dirs.addAll(Arrays.asList("/usr/bin", "/usr/local/bin", "/bin", "/usr/X11R6/bin"));

        for (String dir : dirs) {
            if (dir.isBlank())
                continue;
            File f = new File(dir, name);
            if (f.isFile() && f.canExecute())
                return f.getAbsolutePath();
        }
        return null;
    }

    private static void bootstrapDedicated() throws Exception {
        MCEFPlatform platform = MCEFPlatform.getPlatform();
        File librariesDir = FMLPaths.GAMEDIR.get().resolve("mods").resolve("mcef-libraries").toFile();
        librariesDir.mkdirs();

        // The java-cef natives silently drop ALL injected input events when
        // org.lwjgl.glfw.GLFW cannot be resolved - which is always the case on
        // dedicated servers. Inject a constants-only stub so clicks/typing work.
        GlfwStubInjector.ensureGlfwAvailable(FMLPaths.GAMEDIR.get());

        System.setProperty("mcef.libraries.path", librariesDir.getCanonicalPath());
        System.setProperty("jcef.path", new File(librariesDir, platform.getNormalizedName()).getCanonicalPath());

        String commit = MCEF.getJavaCefCommit();
        Log.info("java-cef commit: %s", commit);

        ensureNativesInstalled(librariesDir, platform, commit);

        // Ensure binaries are executable on unix
        if (platform.isLinux()) {
            setUnixExecutable(new File(librariesDir, platform.getNormalizedName() + "/jcef_helper"));
        } else if (platform.isMacOS()) {
            String base = platform.getNormalizedName() + "/jcef_app.app/Contents/Frameworks/";
            setUnixExecutable(new File(librariesDir, base + "jcef Helper.app/Contents/MacOS/jcef Helper"));
            setUnixExecutable(new File(librariesDir, base + "jcef Helper (GPU).app/Contents/MacOS/jcef Helper (GPU)"));
            setUnixExecutable(new File(librariesDir, base + "jcef Helper (Plugin).app/Contents/MacOS/jcef Helper (Plugin)"));
            setUnixExecutable(new File(librariesDir, base + "jcef Helper (Renderer).app/Contents/MacOS/jcef Helper (Renderer)"));
        }

        ArrayList<String> switches = new ArrayList<>(Arrays.asList(
                "--autoplay-policy=no-user-gesture-required",
                "--disable-web-security",
                "--enable-widevine-cdm",
                "--disable-gpu",
                "--disable-gpu-compositing",
                // Keep Chromium from installing its own SIGSEGV/SIGTRAP handlers
                // over the JVM's. HotSpot relies on benign SIGSEGVs for implicit
                // null checks in JIT-compiled code; with Chromium's handler in
                // place one of those (e.g. during world generation) kills the
                // whole server process.
                "--disable-in-process-stack-traces"
        ));

        if (platform.isLinux()) {
            // Dedicated servers usually run as root, where Chromium's sandbox
            // cannot start; without this the helper processes die on launch.
            switches.add("--no-sandbox");
            switches.add("--disable-setuid-sandbox");

            // Containers/VPS often mount a tiny /dev/shm (64MB). Chromium maps
            // frame buffers there and segfaults once it fills up - typically
            // right after the first page starts rendering. Use /tmp instead.
            switches.add("--disable-dev-shm-usage");

            // The zygote occasionally fails to spawn helper processes on busy
            // headless hosts; Chromium then FATALs ("GPU process isn't usable")
            // and takes the whole JVM down with it. The zygote is pointless
            // without the sandbox anyway, so launch helpers directly and never
            // give up on GPU process restarts.
            switches.add("--no-zygote");
            switches.add("--disable-gpu-process-crash-limit");

            // CEF's windowless renderer still needs an X server; it cannot use
            // Chromium's headless Ozone backend. If DISPLAY is missing (or points
            // to a dead X server - CefInitialize would hard-exit the whole JVM!),
            // start a private Xvfb and point CEF at it.
            String display = System.getenv("DISPLAY");
            if (display != null && !display.isBlank() && !isLocalDisplayAlive(display)) {
                Log.warning("DISPLAY is set to '%s' but that X display does not exist; ignoring it. "
                        + "CEF would otherwise crash the whole server on startup.", display);
                display = null;
            }

            if (display == null || display.isBlank()) {
                String privateDisplay = startPrivateXvfb();
                // Chromium honors --display, but the native side may also read the
                // real environment variable, so set both when possible.
                if (setNativeEnv("DISPLAY", privateDisplay))
                    Log.info("Set DISPLAY=%s in the process environment.", privateDisplay);
                switches.add("--display=" + privateDisplay);
                Log.info("Using private X display %s for server-side CEF.", privateDisplay);
            } else {
                Log.info("Using inherited X display %s for server-side CEF.", display);
            }
        }

        for (String sw : CommonConfig.Stream.extraCefSwitches) {
            if (sw != null && !sw.isBlank())
                switches.add(sw.trim());
        }
        String[] cefSwitches = switches.toArray(new String[0]);
        Log.info("CEF switches: %s", String.join(" ", switches));

        // CEF's native initialization replaces the JVM's POSIX signal handlers.
        // HotSpot depends on its SIGSEGV handler for implicit null checks in
        // JIT-compiled code, so a stomped handler kills the whole server the
        // next time one fires. Snapshot the JVM's handlers now and restore them
        // as soon as CEF is initialized.
        Object[] savedSignals = platform.isLinux() ? saveSignalHandlers() : null;

        try {
            if (!CefApp.startup(cefSwitches))
                throw new IllegalStateException("CefApp.startup() failed");
        } catch (UnsatisfiedLinkError e) {
            // Typical on minimal server distros: libcef.so needs shared libraries
            // that desktop systems have but server images don't.
            throw new IllegalStateException(
                    "Failed to load the CEF native libraries. On minimal Linux servers, install Chromium's runtime dependencies first:\n"
                    + "  Debian/Ubuntu: apt install libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0 "
                    + "libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2 libpango-1.0-0 libcairo2 libxss1 libxtst6 libexpat1\n"
                    + "  Fedora/RHEL:   dnf install nss nspr atk at-spi2-atk cups-libs libdrm libxkbcommon libXcomposite libXdamage "
                    + "libXfixes libXrandr mesa-libgbm alsa-lib pango cairo libXScrnSaver libXtst expat", e);
        }

        CefSettings cefSettings = new CefSettings();
        cefSettings.windowless_rendering_enabled = true;
        if (!CommonConfig.Stream.incognito) // incognito (default): no cache path -> fully in-memory
            cefSettings.cache_path = FMLPaths.GAMEDIR.get().resolve("wd_cef_cache").toAbsolutePath().toString();
        cefSettings.background_color = cefSettings.new ColorType(0, 255, 255, 255);
        cefSettings.user_agent_product = "MCEF/2";

        cefApp = CefApp.getInstance(cefSwitches, cefSettings);
        cefClient = cefApp.createClient();

        // Capture PCM audio from server-side browsers (Opus-encoded + streamed to viewers)
        cefClient.addAudioHandler(ServerAudioRouter.INSTANCE);

        // target="_blank" links must navigate the same view instead of opening
        // an (invisible) popup window
        installPopupRedirect(cefClient);

        // Auto-reload screens whose render process crashes
        installCrashRecovery(cefClient);

        // Keep ScreenData.url in sync when pages navigate (link clicks etc.)
        cefClient.addDisplayHandler(new org.cef.handler.CefDisplayHandlerAdapter() {
            @Override
            public void onAddressChange(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, String url) {
                ServerBrowserManager.onBrowserAddressChange(browser, url);
            }
        });

        // Register the same custom schemes the client uses
        cefApp.registerSchemeHandlerFactory("mod", "", (browser, frame, schemeName, request) ->
                new ServerModScheme(request.getURL()));
        cefApp.registerSchemeHandlerFactory("webdisplaystogether", "", (browser, frame, schemeName, request) ->
                new ServerFileScheme(request.getURL()));

        if (savedSignals != null && restoreSignalHandlers(savedSignals))
            Log.info("Restored the JVM's signal handlers after CEF initialization.");
    }

    // ------------------------------------------------------------------
    // JVM signal handler protection (Linux)
    // ------------------------------------------------------------------

    /** Signals HotSpot installs handlers for and CEF/Chromium likes to replace. */
    private static final int[] GUARDED_SIGNALS = {4 /*ILL*/, 5 /*TRAP*/, 7 /*BUS*/, 8 /*FPE*/, 11 /*SEGV*/, 13 /*PIPE*/, 25 /*XFSZ*/};

    /**
     * Snapshots the current sigaction for each guarded signal using JNA. The
     * struct contents are treated as opaque bytes; they are only ever passed
     * back to sigaction() unchanged.
     */
    private static Object[] saveSignalHandlers() {
        try {
            Class<?> nativeLibrary = Class.forName("com.sun.jna.NativeLibrary");
            Object libc = nativeLibrary.getMethod("getInstance", String.class).invoke(null, "c");
            Object sigaction = nativeLibrary.getMethod("getFunction", String.class).invoke(libc, "sigaction");
            Class<?> memoryClass = Class.forName("com.sun.jna.Memory");
            java.lang.reflect.Method invokeInt = Class.forName("com.sun.jna.Function")
                    .getMethod("invokeInt", Object[].class);

            Object[] saved = new Object[GUARDED_SIGNALS.length];
            for (int i = 0; i < GUARDED_SIGNALS.length; i++) {
                Object mem = memoryClass.getConstructor(long.class).newInstance(512L);
                int rc = (Integer) invokeInt.invoke(sigaction, (Object) new Object[]{GUARDED_SIGNALS[i], null, mem});
                saved[i] = (rc == 0) ? mem : null;
            }
            return saved;
        } catch (Throwable t) {
            Log.warning("Could not snapshot the JVM signal handlers (JNA unavailable?): %s", t.toString());
            return null;
        }
    }

    /** Puts the snapshotted signal handlers back. */
    private static boolean restoreSignalHandlers(Object[] saved) {
        try {
            Class<?> nativeLibrary = Class.forName("com.sun.jna.NativeLibrary");
            Object libc = nativeLibrary.getMethod("getInstance", String.class).invoke(null, "c");
            Object sigaction = nativeLibrary.getMethod("getFunction", String.class).invoke(libc, "sigaction");
            java.lang.reflect.Method invokeInt = Class.forName("com.sun.jna.Function")
                    .getMethod("invokeInt", Object[].class);

            boolean ok = true;
            for (int i = 0; i < GUARDED_SIGNALS.length; i++) {
                if (saved[i] == null)
                    continue;
                int rc = (Integer) invokeInt.invoke(sigaction, (Object) new Object[]{GUARDED_SIGNALS[i], saved[i], null});
                ok &= (rc == 0);
            }
            return ok;
        } catch (Throwable t) {
            Log.warning("Could not restore the JVM signal handlers: %s", t.toString());
            return false;
        }
    }

    /** Classpath directory holding the java-cef natives archives when bundled at build time. */
    private static final String BUNDLED_NATIVES_ROOT = "/wd_natives/";

    /** Where MCEF publishes the java-cef builds. Only ever quoted in setup instructions. */
    private static final String NATIVES_HOST = "https://mcef-download.cinemamod.com";

    /** Written next to vanilla's eula.txt when the natives turn out to be missing. */
    private static final String SETUP_FILE_NAME = "webdisplaystogether-setup.txt";

    /** Set while screens are off because the java-cef natives are not installed. */
    private static volatile boolean setupPending = false;

    /** How long before the same operator is reminded about the pending setup again. */
    private static final long SETUP_REMINDER_COOLDOWN_MS = 5 * 60 * 1000L;
    private static final Map<UUID, Long> setupReminded = new ConcurrentHashMap<>();

    /** Thrown when the server owner still has something to do before CEF can start. */
    private static final class SetupRequiredException extends IOException {
        SetupRequiredException(String msg) {
            super(msg);
        }
    }

    private static File setupFile() {
        return FMLPaths.GAMEDIR.get().resolve(SETUP_FILE_NAME).toFile();
    }

    /**
     * How to put the java-cef natives in place. The mod never fetches anything
     * itself, so on a dedicated server this is the whole install procedure.
     */
    private static String setupInstructions(MCEFPlatform platform, File platformDir, String commit) {
        String archive = platform.getNormalizedName() + ".tar.gz";
        return "Download " + NATIVES_HOST + "/java-cef-builds/" + commit + "/" + archive + "\n"
                + "(its SHA-256 is published next to it as " + archive + ".sha256) and extract it\n"
                + "so that its contents end up in " + platformDir.getAbsolutePath() + "\n"
                + "These are the same files MCEF installs on clients, so copying the\n"
                + "mods/mcef-libraries folder off a machine that already ran the client works too.";
    }

    /**
     * Drops the install instructions next to vanilla's eula.txt, where a server
     * owner will actually find them, instead of only in a console line that has
     * long scrolled away by the time anyone notices the screens are black.
     */
    private static void writeSetupFile(MCEFPlatform platform, File platformDir, String commit) {
        File file = setupFile();
        try {
            Files.write(file.toPath(), (
                    "WebDisplaysTogether: screens are disabled on this server.\n"
                    + "\n"
                    + "Screens are rendered by Chromium (java-cef), which this mod neither ships\n"
                    + "nor downloads. Install it once, by hand:\n"
                    + "\n"
                    + setupInstructions(platform, platformDir, commit) + "\n"
                    + "\n"
                    + "Then restart the server. Everything else keeps working in the meantime.\n"
                    + "You can delete this file; it is rewritten whenever the natives are missing.\n"
                    + "\n"
                    + "A headless Linux server also needs Chromium's shared libraries and an X\n"
                    + "display. The mod prints the package list for your distribution on startup\n"
                    + "and starts its own Xvfb if no display is available.\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.warning("Could not create %s: %s", file.getAbsolutePath(), e.toString());
        }
    }

    /**
     * Privately reminds an operator that the natives still have to be installed.
     * Building a multiblock screen fires once per block placed, so the same
     * player is only told again after a cooldown.
     */
    public static void remindOperatorAboutSetup(Player player) {
        if (!setupPending || !(player instanceof ServerPlayer) || !player.hasPermissions(2))
            return;

        long now = System.currentTimeMillis();
        Long last = setupReminded.get(player.getUUID());
        if (last != null && now - last < SETUP_REMINDER_COOLDOWN_MS)
            return;

        setupReminded.put(player.getUUID(), now);
        Util.toast(player, ChatFormatting.GOLD, "nativesMissing", SETUP_FILE_NAME);
    }

    /**
     * Makes sure the java-cef natives are in mods/mcef-libraries. Local builds
     * may bundle the archive inside the jar; the published jars cannot (mod
     * hosting sites reject executables inside archives) and the mod never
     * fetches anything over the network, so there they have to be installed by
     * hand, exactly like any other server-side native library.
     */
    private static void ensureNativesInstalled(File librariesDir, MCEFPlatform platform, String commit) throws IOException {
        File platformDir = new File(librariesDir, platform.getNormalizedName());
        File markerFile = new File(librariesDir, platform.getNormalizedName() + ".commit");

        String installed = markerFile.isFile()
                ? new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8).trim()
                : null;
        if (platformDir.isDirectory() && commit.equals(installed)) {
            Log.info("java-cef natives are already installed.");
            return;
        }

        String resource = BUNDLED_NATIVES_ROOT + platform.getNormalizedName() + ".tar.gz";
        File archive = new File(librariesDir, platform.getNormalizedName() + ".tar.gz.tmp");

        if (ServerCefManager.class.getResource(resource) == null) {
            // No marker file usually just means the natives were installed by
            // hand or copied over from a client, which is the expected setup.
            if (platformDir.isDirectory()) {
                if (installed == null)
                    Log.info("Using the java-cef natives found in %s.", platformDir.getAbsolutePath());
                else
                    Log.warning("The java-cef natives in %s were installed for commit %s but MCEF expects %s; "
                            + "using them as they are.", platformDir.getAbsolutePath(), installed, commit);
                return;
            }

            setupPending = true;
            writeSetupFile(platform, platformDir, commit);
            throw new SetupRequiredException("Chromium (java-cef) is not installed on this server.\n"
                    + setupInstructions(platform, platformDir, commit) + "\n"
                    + "These instructions are also in " + setupFile().getAbsolutePath());
        }

        String bundledCommit = readBundledText(BUNDLED_NATIVES_ROOT + "commit.txt");
        if (bundledCommit != null && !bundledCommit.equals(commit)) {
            if (platformDir.isDirectory()) {
                Log.warning("The bundled java-cef natives are for commit %s but MCEF expects %s; "
                        + "keeping the natives already present in %s.", bundledCommit, commit, platformDir.getAbsolutePath());
                return;
            }
            throw new IOException("The java-cef natives bundled in this jar are for commit " + bundledCommit
                    + " but the installed MCEF version expects commit " + commit + ".\n"
                    + "Use the mod build that matches your MCEF version.");
        }

        Log.info("Installing the bundled java-cef natives (%s)...", platform.getNormalizedName());

        // Copy the archive out of the jar first: the tar.gz is ~150MB and
        // the extractor needs to read it as a plain stream anyway.
        try (InputStream in = new BufferedInputStream(ServerCefManager.class.getResourceAsStream(resource), 65536);
             FileOutputStream out = new FileOutputStream(archive)) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) != -1)
                out.write(buf, 0, read);
        }

        String expectedSha = readBundledText(BUNDLED_NATIVES_ROOT + platform.getNormalizedName() + ".tar.gz.sha256");

        try {
            if (expectedSha != null) {
                expectedSha = expectedSha.trim().split("\\s+")[0];
                String actualSha = sha256Hex(archive);
                if (!expectedSha.equalsIgnoreCase(actualSha))
                    throw new IOException("The " + platform.getNormalizedName() + ".tar.gz natives archive is corrupt (expected sha256 "
                            + expectedSha + ", got " + actualSha + "). Delete " + archive.getAbsolutePath() + " and try again.");
            }

            Log.info("Extracting java-cef natives...");
            extractTarGz(archive, librariesDir);
        } finally {
            archive.delete();
        }

        Files.write(markerFile.toPath(), commit.getBytes(StandardCharsets.UTF_8));
        Log.info("java-cef natives installed.");
    }

    /** Reads a small classpath resource as trimmed UTF-8 text, or null if absent. */
    private static String readBundledText(String resource) {
        try (InputStream is = ServerCefManager.class.getResourceAsStream(resource)) {
            if (is == null)
                return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static String sha256Hex(File file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream is = new BufferedInputStream(new FileInputStream(file), 65536)) {
                byte[] buf = new byte[65536];
                int read;
                while ((read = is.read(buf)) != -1)
                    md.update(buf, 0, read);
            }

            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest())
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /**
     * Minimal tar.gz extractor (ustar + GNU long names) so the dedicated server
     * does not need commons-compress.
     */
    private static void extractTarGz(File archive, File outputDir) throws IOException {
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(archive), 65536))) {
            byte[] header = new byte[512];
            String pendingLongName = null;

            while (true) {
                if (!readFully(in, header))
                    break;

                boolean empty = true;
                for (byte b : header) {
                    if (b != 0) {
                        empty = false;
                        break;
                    }
                }
                if (empty)
                    continue; // end-of-archive padding

                String name = extractString(header, 0, 100);
                long size = parseOctal(header, 124, 12);
                byte type = header[156];

                // ustar prefix field
                String magic = extractString(header, 257, 6);
                if (magic.startsWith("ustar")) {
                    String prefix = extractString(header, 345, 155);
                    if (!prefix.isEmpty())
                        name = prefix + "/" + name;
                }

                if (pendingLongName != null) {
                    name = pendingLongName;
                    pendingLongName = null;
                }

                long padded = (size + 511) & ~511L;

                if (type == 'L') { // GNU long name
                    byte[] data = new byte[(int) size];
                    if (!readFully(in, data))
                        throw new IOException("Unexpected EOF in tar");
                    pendingLongName = new String(data, StandardCharsets.UTF_8).trim();
                    skipFully(in, padded - size);
                    continue;
                }

                if (type == '5' || name.endsWith("/")) { // directory
                    new File(outputDir, name).mkdirs();
                    skipFully(in, padded);
                    continue;
                }

                if (type != 0 && type != '0') { // link or other special entry: skip content
                    skipFully(in, padded);
                    continue;
                }

                File out = new File(outputDir, name);
                File parent = out.getParentFile();
                if (parent != null)
                    parent.mkdirs();

                try (FileOutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    long remaining = size;
                    while (remaining > 0) {
                        int read = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                        if (read < 0)
                            throw new IOException("Unexpected EOF in tar");
                        os.write(buf, 0, read);
                        remaining -= read;
                    }
                }
                skipFully(in, padded - size);
            }
        }
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int read = in.read(buf, off, buf.length - off);
            if (read < 0)
                return off != 0;
            off += read;
        }
        return true;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) {
                if (in.read() < 0)
                    return;
                skipped = 1;
            }
            n -= skipped;
        }
    }

    private static String extractString(byte[] buf, int off, int len) {
        int end = off;
        while (end < off + len && buf[end] != 0)
            end++;
        return new String(buf, off, end - off, StandardCharsets.UTF_8).trim();
    }

    private static long parseOctal(byte[] buf, int off, int len) {
        long result = 0;
        for (int i = off; i < off + len; i++) {
            byte b = buf[i];
            if (b == 0 || b == ' ')
                continue;
            if (b < '0' || b > '7')
                break;
            result = (result << 3) + (b - '0');
        }
        return result;
    }

    private static void setUnixExecutable(File file) {
        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        perms.add(PosixFilePermission.GROUP_READ);
        perms.add(PosixFilePermission.GROUP_EXECUTE);
        perms.add(PosixFilePermission.OTHERS_READ);
        perms.add(PosixFilePermission.OTHERS_EXECUTE);

        try {
            Files.setPosixFilePermissions(file.toPath(), perms);
        } catch (IOException e) {
            Log.warning("Failed to set %s as executable: %s", file, e.getMessage());
        }
    }
}
