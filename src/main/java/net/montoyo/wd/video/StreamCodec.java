package net.montoyo.wd.video;

import net.montoyo.wd.utilities.Log;

import static org.bytedeco.ffmpeg.global.avcodec.*;

/**
 * Video codec used for the screen stream. The server picks the best encoder
 * available in its FFmpeg build and tags every frame packet with the codec's
 * wire id, so clients always know what to decode.
 *
 * VP9 compresses screen content roughly 30% better than VP8 at the same
 * bitrate, which is what makes 720p fit into the ~3 Mbit/s per-player budget.
 */
public enum StreamCodec {
    VP8((byte) 0, AV_CODEC_ID_VP8, "libvpx"),
    VP9((byte) 1, AV_CODEC_ID_VP9, "libvpx-vp9");

    public final byte wireId;
    public final int avCodecId;
    public final String encoderName;

    StreamCodec(byte wireId, int avCodecId, String encoderName) {
        this.wireId = wireId;
        this.avCodecId = avCodecId;
        this.encoderName = encoderName;
    }

    public static StreamCodec fromWireId(byte id) {
        return id == 1 ? VP9 : VP8;
    }

    private static volatile StreamCodec best; // cached probe result

    /**
     * The codec the server should encode with: VP9 when the FFmpeg build has
     * a VP9 encoder, VP8 otherwise. Only call with the FFmpeg natives loaded.
     */
    public static StreamCodec pickEncoder() {
        StreamCodec cached = best;
        if (cached != null)
            return cached;

        synchronized (StreamCodec.class) {
            if (best == null) {
                boolean hasVp9 = avcodec_find_encoder_by_name(VP9.encoderName) != null
                        || avcodec_find_encoder(VP9.avCodecId) != null;
                best = hasVp9 ? VP9 : VP8;
                Log.info("Screen stream codec: %s%s", best,
                        hasVp9 ? "" : " (no VP9 encoder in this FFmpeg build)");
            }
            return best;
        }
    }
}
