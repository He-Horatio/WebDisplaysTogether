package net.montoyo.wd.serverbrowser;

import net.montoyo.wd.config.CommonConfig;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.video.StreamCodec;
import net.montoyo.wd.video.VideoEncoder;

import java.nio.ByteBuffer;

/**
 * Resolves the effective stream quality settings, sized for a per-player
 * bandwidth budget of roughly 3 Mbit/s. Config values of 0 mean "automatic":
 * <ul>
 * <li>the video bitrate defaults to {@link #AUTO_BUDGET_KBPS}, leaving room
 * for the Opus audio stream and Minecraft's own traffic inside the budget;</li>
 * <li>the stream resolution is capped twice: by a one-off encode benchmark
 * (CPU: one stream must never eat more than a fraction of one core), and by
 * the bitrate itself (quality: enough bits per pixel that motion stays clean
 * instead of smearing into blocks - a smaller, clean picture upscaled onto
 * the in-world screen looks far better than a big blocky one).</li>
 * </ul>
 */
public final class StreamQuality {
    private StreamQuality() {
    }

    /**
     * Default video bitrate (steady state). Opus audio adds 128 kbit/s,
     * keeping one watching player at ~3 Mbit/s on average; short peaks
     * (keyframes, heavy motion) may burst up to ~5 Mbit/s via the encoder's
     * VBV settings in {@link VideoEncoder}.
     */
    private static final int AUTO_BUDGET_KBPS = 2800;

    /**
     * Minimum bits per pixel per frame before motion degrades into blocks.
     * VP9 compresses screen content noticeably better than VP8, so it can
     * afford more pixels out of the same bitrate budget: at 0.09 bpp the
     * default budget covers exactly 1280x720 at 30 fps (921,600 pixels vs a
     * 925,926 pixel cap), so VP9 servers stream real 720p.
     */
    private static final double MIN_BPP_VP8 = 0.14;
    private static final double MIN_BPP_VP9 = 0.09;

    /**
     * Comfortable bits per pixel per frame used to size the bitrate for the
     * resolution actually being streamed. Without this a CPU-capped 360p
     * stream would burn the full 720p budget (2800 kbit/s for a picture that
     * looks perfect at ~1000), needlessly congesting the link - and video
     * hogging the shared TCP connection is exactly what starves the audio
     * stream into crackling.
     */
    private static final double GOOD_BPP_VP8 = 0.22;
    private static final double GOOD_BPP_VP9 = 0.15;

    private static final int BENCH_W = 640, BENCH_H = 360, BENCH_FRAMES = 40;
    private static volatile int autoMaxHeight = 0; // 0 = not measured yet

    /** Effective bitrate ceiling for one screen's video stream, in kbit/s. */
    public static int bitrateKbps() {
        int cfg = CommonConfig.Stream.streamBitrateKbps;
        return cfg > 0 ? cfg : AUTO_BUDGET_KBPS;
    }

    /**
     * Bitrate for a stream of the given actual size: scaled with the pixel
     * count (see {@link #GOOD_BPP_VP9}) and capped by the budget / config.
     */
    public static int bitrateKbpsFor(long pixels, int fps, StreamCodec codec) {
        int cfg = CommonConfig.Stream.streamBitrateKbps;
        if (cfg > 0)
            return cfg;

        double bpp = codec == StreamCodec.VP9 ? GOOD_BPP_VP9 : GOOD_BPP_VP8;
        int scaled = (int) Math.round(pixels * Math.max(1, fps) * bpp / 1000.0);
        return Math.max(500, Math.min(AUTO_BUDGET_KBPS, scaled));
    }

    /**
     * Absolute pixel cap regardless of CPU: the bitrate-quality cap clamped
     * to the 360p..720p auto range (an explicit stream_max_height overrides).
     * Runtime adaptation may push the stream up to this bound when measured
     * encode times show real headroom, even past the startup benchmark.
     */
    public static long hardMaxPixels(int fps, StreamCodec codec) {
        int cfgH = CommonConfig.Stream.streamMaxHeight;
        if (cfgH > 0)
            return heightToPixels(cfgH);

        double bpp = codec == StreamCodec.VP9 ? MIN_BPP_VP9 : MIN_BPP_VP8;
        long rateCap = (long) (bitrateKbps() * 1000.0 / (Math.max(1, fps) * bpp));

        return Math.max(heightToPixels(360), Math.min(heightToPixels(720), rateCap));
    }

    /**
     * Initial pixel cap (any aspect ratio): {@link #hardMaxPixels} further
     * limited by the one-off CPU benchmark. The benchmark runs cold (JIT,
     * CEF startup) and tends to be pessimistic, so this is only the starting
     * point; runtime measurements adjust from here.
     * Only call with FFmpeg natives loaded.
     */
    public static long maxPixels(int fps, StreamCodec codec) {
        int cfgH = CommonConfig.Stream.streamMaxHeight;
        if (cfgH > 0)
            return heightToPixels(cfgH);

        long cpuCap = heightToPixels(cpuMaxHeight(codec, fps));
        return Math.max(heightToPixels(360), Math.min(hardMaxPixels(fps, codec), cpuCap));
    }

    private static long heightToPixels(int h) {
        return (long) h * h * 16 / 9;
    }

    private static int cpuMaxHeight(StreamCodec codec, int fps) {
        int auto = autoMaxHeight;
        return auto > 0 ? auto : benchmark(codec, fps);
    }

    private static synchronized int benchmark(StreamCodec codec, int fps) {
        if (autoMaxHeight > 0)
            return autoMaxHeight;

        int fpsN = Math.max(1, fps);
        int result = 720; // fallback if the benchmark fails
        try {
            ByteBuffer frame = ByteBuffer.allocateDirect(BENCH_W * BENCH_H * 4);
            long nanos;

            // High bitrate so the rate control never skips frames and the
            // timing reflects real encoding work.
            try (VideoEncoder enc = new VideoEncoder(codec, BENCH_W, BENCH_H, fpsN, 8000)) {
                fill(frame, 0);
                enc.encode(frame, BENCH_W, BENCH_H, true); // warmup + keyframe

                long t0 = System.nanoTime();
                for (int i = 1; i <= BENCH_FRAMES; i++) {
                    fill(frame, i);
                    enc.encode(frame, BENCH_W, BENCH_H, false);
                }
                nanos = System.nanoTime() - t0;
            }

            double msPerFrame = Math.max(0.05, nanos / 1e6 / BENCH_FRAMES);
            // Keep one stream's encode below ~25% of a frame interval. The
            // encoder is not alone on this machine: Chromium itself (layout,
            // JS, decoding the page's own video) usually costs more CPU than
            // the video encode, and there may be several screens. Smoothness
            // beats sharpness, so err on the small side.
            double budgetMs = (1000.0 / fpsN) * 0.25;
            long maxPixels = (long) (BENCH_W * BENCH_H * budgetMs / msPerFrame);
            result = clampHeight((int) Math.sqrt(maxPixels * 9.0 / 16.0));

            Log.info("Stream auto quality: %s benchmark encoded %dx%d in %.1f ms/frame -> CPU cap ~%dp "
                            + "(set stream_max_height in the config to override)",
                    codec, BENCH_W, BENCH_H, msPerFrame, result);
        } catch (Throwable t) {
            Log.warningEx("Stream quality benchmark failed; defaulting to a " + result + "p CPU cap", t);
        }

        autoMaxHeight = result;
        return result;
    }

    private static int clampHeight(int h) {
        return Math.max(360, Math.min(1080, h)) & ~1;
    }

    /** Moving colored blocks + gradient: motion comparable to playing web video. */
    private static void fill(ByteBuffer buf, int t) {
        buf.position(0);
        for (int y = 0; y < BENCH_H; y++) {
            for (int x = 0; x < BENCH_W; x++) {
                int block = (((x + t * 13) / 60) + ((y + t * 7) / 45)) & 3;
                int r = block == 0 ? 220 : (x * 255 / BENCH_W);
                int g = block == 1 ? 200 : (y * 255 / BENCH_H);
                int b = block == 2 ? 240 : ((x + y + t * 5) & 255);
                buf.put((byte) b).put((byte) g).put((byte) r).put((byte) 255);
            }
        }
        buf.position(0);
    }
}
