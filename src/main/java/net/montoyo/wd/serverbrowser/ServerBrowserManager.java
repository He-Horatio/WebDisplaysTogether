package net.montoyo.wd.serverbrowser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.PacketDistributor;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.config.CommonConfig;
import net.montoyo.wd.controls.builtin.ClickControl;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.net.WDNetworkRegistry;
import net.montoyo.wd.net.client_bound.S2CMessageScreenUpdate;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.VideoType;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.math.Vector2i;
import org.cef.browser.CefBrowser;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static net.montoyo.wd.block.PeripheralBlock.point;

/**
 * Server-side registry of streamed screens. Runs on the logical server
 * (dedicated or integrated). All public methods that touch world state must be
 * called from the server thread.
 */
public final class ServerBrowserManager {
    private ServerBrowserManager() {
    }

    private static final ConcurrentHashMap<ScreenKey, StreamedScreen> screens = new ConcurrentHashMap<>();
    private static final AtomicInteger nextStreamId = new AtomicInteger(1);
    private static volatile ScheduledExecutorService encodePool;
    private static long lastMaxWarn = 0;

    static ScheduledExecutorService encodePool() {
        ScheduledExecutorService pool = encodePool;
        if (pool == null) {
            synchronized (ServerBrowserManager.class) {
                if (encodePool == null) {
                    // Several screens can stream at once and each encode takes a
                    // few ms; 2 threads starve quickly and late encodes read as
                    // stutter on every viewer. Scale with the machine, capped
                    // so Chromium and the server thread keep enough cores.
                    int threads = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
                    encodePool = Executors.newScheduledThreadPool(threads, r -> {
                        Thread th = new Thread(r, "WDT-Encoder");
                        th.setDaemon(true);
                        return th;
                    });
                }
                pool = encodePool;
            }
        }
        return pool;
    }

    /** For the single-player direct path (client reads frames from the same JVM). */
    public static StreamedScreen peek(ScreenKey key) {
        return screens.get(key);
    }

    /** Finds the streamed screen owning the given CefBrowser (any thread). */
    public static StreamedScreen findByBrowser(CefBrowser browser) {
        for (StreamedScreen ss : screens.values()) {
            if (ss.ownsBrowser(browser))
                return ss;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Subscriptions (server thread)
    // ------------------------------------------------------------------

    public static void subscribe(ServerPlayer ply, BlockPos pos, BlockSide side) {
        Level level = ply.level();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ScreenBlockEntity tes))
            return;

        ScreenData scr = tes.getScreen(side);
        if (scr == null)
            return;

        // No server browser for local-mode or redstone-disabled screens
        if (scr.browseMode == net.montoyo.wd.core.BrowseMode.LOCAL || tes.isPoweredOff())
            return;

        ScreenKey key = ScreenKey.of(level, pos, side);
        StreamedScreen ss = screens.get(key);

        if (ss == null) {
            if (screens.size() >= CommonConfig.Stream.maxServerBrowsers) {
                long now = System.currentTimeMillis();
                if (now - lastMaxWarn > 10000) {
                    lastMaxWarn = now;
                    Log.warning("Cannot open server browser for %s: limit of %d browsers reached", key, CommonConfig.Stream.maxServerBrowsers);
                }
                return;
            }

            Vector2i size = browserSize(scr);
            String url = WebDisplays.applyBlacklist(scr.url != null ? scr.url : CommonConfig.Browser.homepage);
            ss = new StreamedScreen(key, nextStreamId.getAndIncrement(), url, size.x, size.y);
            screens.put(key, ss);
            ss.ensureBrowser();
            ss.startEncoding();
        }

        boolean local = ply.connection.connection.isMemoryConnection();
        ss.addSubscriber(ply, local);
        Log.info("Player %s subscribed to screen stream %s (local=%b, %d subscriber(s))",
                ply.getName().getString(), key, local, ss.subscribers().size());
    }

    public static void unsubscribe(ServerPlayer ply, BlockPos pos, BlockSide side) {
        StreamedScreen ss = screens.get(ScreenKey.of(ply.level(), pos, side));
        if (ss != null)
            ss.removeSubscriber(ply.getUUID());
    }

    public static void requestKeyframe(ServerPlayer ply, BlockPos pos, BlockSide side) {
        StreamedScreen ss = screens.get(ScreenKey.of(ply.level(), pos, side));
        if (ss != null)
            ss.requestKeyframe();
    }

    /** Delivery-quality report from a viewer; drives the stream's network adaptation. */
    public static void handleFeedback(ServerPlayer ply, BlockPos pos, BlockSide side,
                                      int framesReceived, int videoStalls, int audioGlitches) {
        StreamedScreen ss = screens.get(ScreenKey.of(ply.level(), pos, side));
        if (ss != null)
            ss.onClientFeedback(ply, framesReceived, videoStalls, audioGlitches);
    }

    // ------------------------------------------------------------------
    // Screen lifecycle hooks (server thread)
    // ------------------------------------------------------------------

    public static void onUrlChanged(Level level, BlockPos pos, BlockSide side, String url) {
        StreamedScreen ss = screens.get(ScreenKey.of(level, pos, side));
        if (ss != null)
            ss.loadUrl(url);
    }

    public static void onDisplayChanged(Level level, BlockPos pos, BlockSide side, ScreenData scr) {
        StreamedScreen ss = screens.get(ScreenKey.of(level, pos, side));
        if (ss != null) {
            Vector2i size = browserSize(scr);
            ss.resize(size.x, size.y);
        }
    }

    public static void onScreenRemoved(Level level, BlockPos pos, BlockSide side) {
        StreamedScreen ss = screens.remove(ScreenKey.of(level, pos, side));
        if (ss != null)
            ss.close();
    }

    public static void onScreensRemoved(Level level, BlockPos pos) {
        for (BlockSide side : BlockSide.values())
            onScreenRemoved(level, pos, side);
    }

    // ------------------------------------------------------------------
    // Input injection (server thread)
    // ------------------------------------------------------------------

    public static void injectMouse(Level level, BlockPos pos, BlockSide side, ClickControl.ControlType event, Vector2i vec, int button) {
        StreamedScreen ss = screens.get(ScreenKey.of(level, pos, side));
        if (ss == null) {
            if (event != ClickControl.ControlType.MOVE)
                Log.warning("Dropping %s mouse input for screen %s/%s: no active server browser (no viewers yet?)", event, pos.toShortString(), side);
            return;
        }

        if (ss.getBrowser() == null && event != ClickControl.ControlType.MOVE)
            Log.warning("Dropping %s mouse input for screen %s/%s: browser still starting up", event, pos.toShortString(), side);

        if (event != ClickControl.ControlType.MOVE)
            Log.info("Injecting %s mouse event at %s into server browser for screen %s/%s",
                    event, vec != null ? "(" + vec.x + ", " + vec.y + ")" : "(last pos)", pos.toShortString(), side);

        if (vec != null && event != ClickControl.ControlType.MOVE)
            ss.injectMouse(ClickControl.ControlType.MOVE, vec, -1);

        ss.injectMouse(event, vec, button);
    }

    public static void injectType(Level level, BlockPos pos, BlockSide side, String text) {
        StreamedScreen ss = screens.get(ScreenKey.of(level, pos, side));
        if (ss == null) {
            Log.warning("Dropping keyboard input for screen %s/%s: no active server browser", pos.toShortString(), side);
            return;
        }
        Log.info("Injecting keyboard input into server browser for screen %s/%s", pos.toShortString(), side);
        ss.injectType(text, WebDisplays.GSON);
    }

    // ------------------------------------------------------------------
    // Browser -> world feedback
    // ------------------------------------------------------------------

    /**
     * Called (from any thread) when a server-side browser navigates to a new URL,
     * e.g. because a viewer clicked a link. Mirrors the old client-side
     * updateClientSideURL behavior: keeps ScreenData.url in sync and enforces
     * the blacklist.
     */
    public static void onBrowserAddressChange(CefBrowser browser, String url) {
        for (StreamedScreen ss : screens.values()) {
            ServerScreenBrowser ssb = ss.getBrowser();
            if (ssb != null && (ssb == browser || ssb.getIdentifier() == browser.getIdentifier())) {
                MinecraftServer server = WebDisplays.PROXY.getServer();
                if (server != null)
                    server.execute(() -> applyBrowserUrl(server, ss, url));
                return;
            }
        }
    }

    private static void applyBrowserUrl(MinecraftServer server, StreamedScreen ss, String url) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ss.key.dimension()));
        if (level == null)
            return;

        BlockEntity be = level.getBlockEntity(ss.key.pos());
        if (!(be instanceof ScreenBlockEntity tes))
            return;

        ScreenData scr = tes.getScreen(ss.key.side());
        if (scr == null || url.equals(scr.url))
            return;

        if (WebDisplays.isSiteBlacklisted(url)) {
            ss.loadUrl(WebDisplays.BLACKLIST_URL);
            return;
        }

        // Keep the stream's own URL current too, so crash recovery reopens
        // the page the viewers were actually on, not the original one.
        ss.noteUrl(url);

        scr.url = url;
        scr.videoType = VideoType.getTypeFromURL(url);
        tes.setChanged();

        // keep client GUIs (URL bar etc.) in sync
        WDNetworkRegistry.INSTANCE.send(
                PacketDistributor.NEAR.with(() -> point(level, ss.key.pos())),
                S2CMessageScreenUpdate.setURL(tes, ss.key.side(), url));
    }

    // ------------------------------------------------------------------
    // Maintenance
    // ------------------------------------------------------------------

    private static int tickCounter = 0;

    /** Called every server tick from the main thread. */
    public static void tick(MinecraftServer server) {
        if (++tickCounter % 40 != 0)
            return;

        long now = System.currentTimeMillis();
        long idleTimeoutMs = CommonConfig.Stream.browserIdleTimeout * 1000L;
        double maxDistSq = 128.0 * 128.0;

        for (Map.Entry<ScreenKey, StreamedScreen> entry : screens.entrySet()) {
            StreamedScreen ss = entry.getValue();
            ScreenKey key = entry.getKey();

            // Retry browser creation (CEF may not have been ready yet)
            ss.ensureBrowser();

            // Detect dead/hung render processes and recreate their browser
            ss.checkBrowserHealth(now);

            // Prune stale subscribers
            for (Map.Entry<UUID, StreamedScreen.Subscriber> sub : ss.subscribers().entrySet()) {
                ServerPlayer ply = server.getPlayerList().getPlayer(sub.getKey());
                if (ply == null
                        || ply.level().dimension().location().equals(key.dimension()) == false
                        || ply.distanceToSqr(key.pos().getX() + 0.5, key.pos().getY() + 0.5, key.pos().getZ() + 0.5) > maxDistSq) {
                    ss.removeSubscriber(sub.getKey());
                } else {
                    sub.getValue().player = ply; // refresh (respawn creates a new instance)
                }
            }

            if (ss.hasSubscribers())
                ss.lastViewerTime = now;
            else if (now - ss.lastViewerTime > idleTimeoutMs) {
                Log.info("Closing idle server browser for %s", key);
                screens.remove(key);
                ss.close();
            }
        }
    }

    public static void shutdownAll() {
        for (StreamedScreen ss : screens.values())
            ss.close();
        screens.clear();

        synchronized (ServerBrowserManager.class) {
            if (encodePool != null) {
                encodePool.shutdown();
                encodePool = null;
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static ScreenData getScreenData(Level level, BlockPos pos, BlockSide side) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ScreenBlockEntity tes))
            return null;

        return tes.getScreen(side);
    }

    private static Vector2i browserSize(ScreenData scr) {
        if (scr.rotation != null && scr.rotation.isVertical)
            return new Vector2i(scr.resolution.y, scr.resolution.x);
        else
            return new Vector2i(scr.resolution.x, scr.resolution.y);
    }
}
