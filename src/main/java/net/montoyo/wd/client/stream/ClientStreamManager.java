package net.montoyo.wd.client.stream;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.montoyo.wd.net.WDNetworkRegistry;
import net.montoyo.wd.net.client_bound.S2CMessageStreamFrame;
import net.montoyo.wd.net.server_bound.C2SMessageStreamCtrl;
import net.montoyo.wd.serverbrowser.ScreenKey;
import net.montoyo.wd.utilities.data.BlockSide;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side registry of active screen streams (render thread only).
 */
public final class ClientStreamManager {
    private ClientStreamManager() {
    }

    private static final Map<ScreenKey, ClientScreenStream> streams = new HashMap<>();

    private static final long FEEDBACK_INTERVAL_MS = 2000;
    private static long lastFeedbackMs = 0;

    /**
     * Called once per client tick: every 2s, reports per-screen delivery
     * quality (frame arrival stalls, audio underruns) to the server, which
     * lowers the stream quality until the viewer's link keeps up.
     */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (streams.isEmpty() || mc.level == null)
            return;

        // Fallback pacing pump for screens that are not being rendered
        // (the render path pumps much more often while visible)
        for (ClientScreenStream stream : streams.values())
            stream.pumpFrameBuffer();

        long now = System.currentTimeMillis();
        if (now - lastFeedbackMs < FEEDBACK_INTERVAL_MS)
            return;
        lastFeedbackMs = now;

        if (mc.getConnection() == null || mc.hasSingleplayerServer())
            return;

        for (ClientScreenStream stream : streams.values()) {
            ScreenKey key = stream.getKey();
            int[] video = stream.pollFeedback();
            int audioGlitches = net.montoyo.wd.client.audio.ScreenAudioManager.pollGlitches(key.pos(), key.side());

            if (video[0] > 0 || audioGlitches > 0)
                WDNetworkRegistry.INSTANCE.sendToServer(
                        C2SMessageStreamCtrl.feedback(key.pos(), key.side(), video[0], video[1], audioGlitches));
        }
    }

    /** Opens (or returns the existing) stream for a screen and subscribes to it. */
    public static ClientScreenStream open(Level level, BlockPos pos, BlockSide side) {
        ScreenKey key = ScreenKey.of(level, pos, side);
        ClientScreenStream stream = streams.get(key);
        if (stream != null)
            return stream;

        stream = new ClientScreenStream(key);
        streams.put(key, stream);
        net.montoyo.wd.utilities.Log.info("Subscribing to screen stream %s", key);
        WDNetworkRegistry.INSTANCE.sendToServer(new C2SMessageStreamCtrl(pos, side, C2SMessageStreamCtrl.ACT_SUBSCRIBE));
        return stream;
    }

    /** Called by {@link ClientScreenStream#close()}; unregisters and notifies the server. */
    static void onStreamClosed(ClientScreenStream stream) {
        streams.remove(stream.getKey());

        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            WDNetworkRegistry.INSTANCE.sendToServer(
                    new C2SMessageStreamCtrl(stream.getKey().pos(), stream.getKey().side(), C2SMessageStreamCtrl.ACT_UNSUBSCRIBE));
        }
    }

    /** Handles an incoming frame chunk (render thread). */
    public static void handleFrame(S2CMessageStreamFrame msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;

        ClientScreenStream stream = streams.get(ScreenKey.of(mc.level, msg.pos, msg.side));
        if (stream != null)
            stream.feed(msg);
    }

    /** Closes every stream (e.g. when leaving the world). */
    public static void closeAll() {
        // close() mutates the map via onStreamClosed, so copy first
        for (ClientScreenStream stream : streams.values().toArray(new ClientScreenStream[0]))
            stream.close();
        streams.clear();
    }
}
