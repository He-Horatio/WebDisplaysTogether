package net.montoyo.wd.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.montoyo.wd.config.ClientConfig;
import net.montoyo.wd.net.client_bound.S2CMessageStreamAudio;
import net.montoyo.wd.serverbrowser.ScreenKey;
import net.montoyo.wd.utilities.data.BlockSide;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Client-side audio hub: one {@link ScreenAudioChannel} per audible screen.
 * Channels are created on the first audio packet and removed when idle or when
 * the screen disappears. All channel access happens on the client main thread;
 * the push* entry points marshal from other threads.
 */
public final class ScreenAudioManager {
    private record ChannelKey(BlockPos pos, BlockSide side) {
    }

    private static final Map<ChannelKey, ScreenAudioChannel> channels = new HashMap<>();

    /**
     * True while at least one screen is audibly playing sound and Minecraft's
     * background music should stay silent. Read from the PlaySoundEvent
     * handler (which cancels new MUSIC sounds) - volatile because sounds can
     * be started from other threads.
     */
    private static volatile boolean duckingMusic = false;

    private ScreenAudioManager() {
    }

    public static boolean isDuckingMusic() {
        return duckingMusic;
    }

    private static ScreenAudioChannel channel(BlockPos pos, BlockSide side) {
        return channels.computeIfAbsent(new ChannelKey(pos.immutable(), side),
                k -> new ScreenAudioChannel(k.pos(), k.side()));
    }

    /** Opus packet from the network (main thread, via packet handler). */
    public static void handleAudio(S2CMessageStreamAudio msg) {
        if (Minecraft.getInstance().level == null)
            return;

        channel(msg.pos, msg.side).handleEncoded(msg.data);
    }

    /** Raw PCM from the integrated server's browser (CEF audio thread). */
    public static void pushFromIntegratedServer(ScreenKey key, float[] stereo48k) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.level == null || !mc.level.dimension().location().equals(key.dimension()))
                return;

            channel(key.pos(), key.side()).pushPcm(stereo48k);
        });
    }

    /** Raw PCM from a local-mode (client-side) browser (CEF audio thread). */
    public static void pushFromLocalBrowser(BlockPos pos, BlockSide side, float[] stereo48k) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.level == null)
                return;

            channel(pos, side).pushPcm(stereo48k);
        });
    }

    /** Called once per client tick from the main thread. */
    public static void tick() {
        if (channels.isEmpty()) {
            duckingMusic = false;
            return;
        }

        if (Minecraft.getInstance().level == null) {
            closeAll();
            return;
        }

        Iterator<ScreenAudioChannel> it = channels.values().iterator();
        while (it.hasNext()) {
            if (it.next().tick())
                it.remove();
        }

        updateMusicDucking();
    }

    /**
     * While any screen is audibly playing, silence Minecraft's background
     * music: stop the track that is currently playing (once, on the rising
     * edge) and let the PlaySoundEvent handler in ClientProxy cancel any new
     * MUSIC-category sound the music manager tries to start.
     */
    private static void updateMusicDucking() {
        if (!ClientConfig.duckMusic) {
            duckingMusic = false;
            return;
        }

        boolean audible = false;
        for (ScreenAudioChannel channel : channels.values()) {
            if (channel.isAudible()) {
                audible = true;
                break;
            }
        }

        if (audible && !duckingMusic)
            Minecraft.getInstance().getSoundManager().stop(null, SoundSource.MUSIC);
        duckingMusic = audible;
    }

    /**
     * Audio glitches (underruns, skips) for one screen since the last poll;
     * 0 if the screen has no active audio. Used for stream quality feedback.
     */
    public static int pollGlitches(BlockPos pos, BlockSide side) {
        ScreenAudioChannel channel = channels.get(new ChannelKey(pos.immutable(), side));
        return channel != null ? channel.pollGlitches() : 0;
    }

    public static void closeAll() {
        for (ScreenAudioChannel channel : channels.values())
            channel.close();
        channels.clear();
        duckingMusic = false;
    }
}
