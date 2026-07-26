package net.montoyo.wd.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.montoyo.wd.audio.OpusDecoder;
import net.montoyo.wd.audio.PcmConverter;
import net.montoyo.wd.core.ScreenSoundMode;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.entity.SpeakerBlockEntity;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.math.Vector3i;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Audio playback for a single screen surface. Depending on the screen's sound
 * mode, audio comes out of:
 * - SCREEN: one mono positional source at the screen surface center
 * - DIRECT: one non-positional stereo source (head-locked)
 * - SPEAKERS: one mono positional source per linked speaker block, with
 *   constant-power channel weights from the speaker's configured position
 *
 * Volume: full within 16 blocks, linear falloff to 0 between 16 and 32 blocks
 * (distance to the screen or to each speaker), scaled by the screen's volume
 * slider (0-300%) and Minecraft's master volume.
 *
 * All methods run on the client main thread.
 */
public final class ScreenAudioChannel {
    private static final double FULL_DIST = 16.0;
    private static final double MAX_DIST = 32.0;
    private static final long IDLE_TIMEOUT_MS = 30000;

    final BlockPos pos;
    final BlockSide side;

    private OpusDecoder decoder;
    private boolean decoderFailed = false;

    private ScreenSoundMode activeMode = null;
    private AlStreamSource main;
    private final Map<SpeakerBlockEntity, AlStreamSource> speakerSources = new HashMap<>();

    long lastPacketTime = System.currentTimeMillis();
    /** Loudest effective AL gain across this channel's sources (set in updateSpatial). */
    private float lastGain = 0.0f;

    ScreenAudioChannel(BlockPos pos, BlockSide side) {
        this.pos = pos;
        this.side = side;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    void handleEncoded(byte[] opusPacket) {
        if (decoderFailed)
            return;

        if (decoder == null) {
            try {
                decoder = new OpusDecoder();
            } catch (Throwable t) {
                decoderFailed = true;
                Log.errorEx("Failed to create Opus decoder; screen audio disabled", t);
                return;
            }
        }

        float[] stereo = decoder.decode(opusPacket);
        if (stereo != null)
            pushPcm(stereo);
    }

    /** Feeds interleaved stereo 48kHz float PCM into the active sources. */
    void pushPcm(float[] stereo) {
        lastPacketTime = System.currentTimeMillis();

        ScreenData scr = findScreen();
        if (scr == null)
            return;

        List<SpeakerBlockEntity> speakers = (scr.soundMode == ScreenSoundMode.SPEAKERS)
                ? ClientSpeakerRegistry.forScreen(pos, side) : List.of();

        // no linked speakers -> fall back to screen playback
        ScreenSoundMode effective = switch (scr.soundMode) {
            case SPEAKERS -> speakers.isEmpty() ? ScreenSoundMode.SCREEN : ScreenSoundMode.SPEAKERS;
            case DIRECT -> ScreenSoundMode.DIRECT;
            default -> ScreenSoundMode.SCREEN;
        };

        if (effective != activeMode) {
            dropSources();
            activeMode = effective;
        }

        // recreate sources that died (AL context recreation, repeated AL errors...)
        if (main != null && !main.isHealthy()) {
            main.close();
            main = null;
        }

        // The volume slider (0-300%) is applied in software on the PCM itself:
        // OpenAL clamps source gains at AL_MAX_GAIN (1.0), so boosts above 100%
        // would silently do nothing if applied on the AL source.
        short[] pcm16 = PcmConverter.toPcm16(stereo, volumeGain(scr.volume));

        switch (effective) {
            case SCREEN -> {
                if (main == null)
                    main = new AlStreamSource(false);
                main.queue(PcmConverter.stereoToMono(pcm16), 1);
            }
            case DIRECT -> {
                if (main == null)
                    main = new AlStreamSource(true);
                main.queue(pcm16, 2);
            }
            case SPEAKERS -> {
                for (SpeakerBlockEntity speaker : speakers) {
                    AlStreamSource src = speakerSources.get(speaker);
                    if (src != null && !src.isHealthy()) {
                        src.close();
                        speakerSources.remove(speaker);
                        src = null;
                    }
                    if (src == null) {
                        src = new AlStreamSource(false);
                        speakerSources.put(speaker, src);
                    }

                    // constant-power pan from the speaker's configured left/right position
                    double angle = (clamp(speaker.getRelX()) + 1.0) * Math.PI / 4.0;
                    float wl = (float) Math.cos(angle);
                    float wr = (float) Math.sin(angle);
                    src.queue(PcmConverter.weightedMono(pcm16, wl, wr), 1);
                }
            }
        }

        updateSpatial(scr, speakers);
    }

    // ------------------------------------------------------------------
    // Per-tick updates
    // ------------------------------------------------------------------

    /** @return true if this channel is dead and should be removed. */
    boolean tick() {
        ScreenData scr = findScreen();
        if (scr == null || System.currentTimeMillis() - lastPacketTime > IDLE_TIMEOUT_MS) {
            close();
            return true;
        }

        List<SpeakerBlockEntity> speakers = (activeMode == ScreenSoundMode.SPEAKERS)
                ? ClientSpeakerRegistry.forScreen(pos, side) : List.of();

        // watchdog: drop dead sources so the next packet recreates them
        if (main != null && !main.isHealthy()) {
            main.close();
            main = null;
        }

        // free sources for speakers that were unlinked/unloaded, and dead sources
        Iterator<Map.Entry<SpeakerBlockEntity, AlStreamSource>> it = speakerSources.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<SpeakerBlockEntity, AlStreamSource> entry = it.next();
            if (!speakers.contains(entry.getKey()) || !entry.getValue().isHealthy()) {
                entry.getValue().close();
                it.remove();
            }
        }

        // restart sources that stopped after an underrun
        if (main != null)
            main.tickKeepAlive();
        for (AlStreamSource src : speakerSources.values())
            src.tickKeepAlive();

        updateSpatial(scr, speakers);
        return false;
    }

    private void updateSpatial(ScreenData scr, List<SpeakerBlockEntity> speakers) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;

        Vec3 listener = mc.gameRenderer.getMainCamera().getPosition();
        // Volume slider is baked into the PCM (see pushPcm); AL gain only carries
        // distance falloff x master volume, which always stays within [0, 1].
        float base = mc.options.getSoundSourceVolume(SoundSource.MASTER);
        Vec3 center = screenCenter(scr);
        float maxGain = 0.0f;

        if (main != null) {
            if (activeMode == ScreenSoundMode.SCREEN)
                main.setPosition(center);
            float gain = base * distanceCurveF(listener.distanceTo(center));
            main.setGain(gain);
            maxGain = gain;
        }

        for (Map.Entry<SpeakerBlockEntity, AlStreamSource> entry : speakerSources.entrySet()) {
            Vec3 sp = Vec3.atCenterOf(entry.getKey().getBlockPos());
            entry.getValue().setPosition(sp);
            float gain = base * distanceCurveF(listener.distanceTo(sp));
            entry.getValue().setGain(gain);
            maxGain = Math.max(maxGain, gain);
        }

        lastGain = maxGain;
    }

    /**
     * True while this screen is actually producing audible sound: packets are
     * still flowing (paused pages stop sending within a second) and at least
     * one source is in range and not muted. Used to duck Minecraft's own
     * background music.
     */
    boolean isAudible() {
        return lastGain > 0.01f
                && (main != null || !speakerSources.isEmpty())
                && System.currentTimeMillis() - lastPacketTime < 1500;
    }

    /** Audible glitches (underruns, skips) across all sources since the last poll. */
    int pollGlitches() {
        int n = 0;
        if (main != null)
            n += main.pollGlitches();
        for (AlStreamSource src : speakerSources.values())
            n += src.pollGlitches();
        return n;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ScreenData findScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return null;

        BlockEntity be = mc.level.getBlockEntity(pos);
        if (!(be instanceof ScreenBlockEntity tes))
            return null;

        return tes.getScreen(side);
    }

    /** Center of the screen surface (the multiblock spans size.x * size.y blocks). */
    private Vec3 screenCenter(ScreenData scr) {
        Vec3 center = Vec3.atCenterOf(pos);
        if (scr.size == null)
            return center;

        Vector3i right = side.right;
        Vector3i up = side.up;
        double dx = (scr.size.x - 1) / 2.0;
        double dy = (scr.size.y - 1) / 2.0;

        return center.add(
                right.x * dx + up.x * dy,
                right.y * dx + up.y * dy,
                right.z * dx + up.z * dy);
    }

    private static double distanceCurve(double dist) {
        if (dist <= FULL_DIST)
            return 1.0;
        if (dist >= MAX_DIST)
            return 0.0;
        return (MAX_DIST - dist) / (MAX_DIST - FULL_DIST);
    }

    private static float distanceCurveF(double dist) {
        return (float) distanceCurve(dist);
    }

    private static float clamp(float v) {
        return Math.max(-1.0f, Math.min(1.0f, v));
    }

    /**
     * Maps the volume slider (0-300) to a PCM gain factor. The default (100)
     * maps to exactly x1: the PCM passes through untouched. The old baseline
     * was x2, which pushed every sample through the tanh soft limiter - web
     * audio is already mastered near full scale, so that distorted ALL sound
     * into a crunchy "overdriven" tone. Values above 100 buy loudness at the
     * price of some (soft-limited) distortion; that is now an explicit user
     * choice instead of the default.
     */
    private static float volumeGain(int volume) {
        if (volume <= 100)
            return volume / 100.0f;
        return 1.0f + (volume - 100) * 0.02f; // 300 -> x5, tanh-limited
    }

    private void dropSources() {
        if (main != null) {
            main.close();
            main = null;
        }
        for (AlStreamSource src : speakerSources.values())
            src.close();
        speakerSources.clear();
    }

    void close() {
        dropSources();
        if (decoder != null) {
            decoder.close();
            decoder = null;
        }
    }
}
