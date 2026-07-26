package net.montoyo.wd.video;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.Pointer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * Real-time VP8/VP9 encoder. Takes BGRA frames (from CEF's onPaint) and
 * produces compressed packets suitable for streaming to clients.
 * Not thread-safe; use from a single encoder thread.
 */
public final class VideoEncoder implements AutoCloseable {
    public static final class EncodedFrame {
        public final byte[] data;
        public final boolean keyframe;

        EncodedFrame(byte[] data, boolean keyframe) {
            this.data = data;
            this.keyframe = keyframe;
        }
    }

    private final StreamCodec codecType;
    private final int width, height;
    private AVCodecContext ctx;
    private AVFrame frame;
    private AVFrame srcFrame; // aligned+padded BGRA staging frame for swscale
    private AVPacket packet;
    private SwsContext sws;
    private int swsSrcW = -1, swsSrcH = -1;
    private long pts = 0;

    /**
     * @param width  output width (rounded down to even)
     * @param height output height (rounded down to even)
     */
    public VideoEncoder(StreamCodec codecType, int width, int height, int fps, int bitrateKbps) {
        this.codecType = codecType;
        this.width = Math.max(2, width & ~1);
        this.height = Math.max(2, height & ~1);

        AVCodec codec = avcodec_find_encoder_by_name(codecType.encoderName);
        if (codec == null)
            codec = avcodec_find_encoder(codecType.avCodecId);
        if (codec == null)
            throw new IllegalStateException(codecType + " encoder not available");

        ctx = avcodec_alloc_context3(codec);
        if (ctx == null)
            throw new IllegalStateException("Could not allocate encoder context");

        // Safety floor (~0.1 bit/pixel/frame) so a misconfigured/zero bitrate
        // never degrades motion into smears.
        long pixels = (long) this.width * this.height;
        int fpsN = Math.max(1, fps);
        int kbps = (int) Math.max(bitrateKbps, pixels * fpsN / 10000);

        ctx.width(this.width);
        ctx.height(this.height);
        ctx.time_base().num(1);
        ctx.time_base().den(fpsN);
        ctx.pix_fmt(AV_PIX_FMT_YUV420P);
        ctx.bit_rate(kbps * 1000L);
        // VBV: steady state stays at the target bitrate (~3 Mbit/s per player
        // with audio), but short peaks - keyframes, heavy motion - may burst
        // up to ~1.7x (~5 Mbit/s) so they don't have to smear into blocks.
        // The buffer is still kept smallish (~0.6s): video and audio share
        // one TCP connection, so a huge keyframe stalls the audio behind it
        // for its whole transmit time - heard as a dropout/crackle. A coarse
        // keyframe is fine; the refine window polishes it within a second.
        ctx.rc_max_rate(kbps * 1700L);
        ctx.rc_buffer_size((int) Math.min(Integer.MAX_VALUE, kbps * 600L)); // ~0.6 seconds
        ctx.gop_size(fpsN * 10); // keyframes are mostly requested on demand
        ctx.max_b_frames(0);
        ctx.thread_count(Math.min(4, Runtime.getRuntime().availableProcessors()));

        // Smoothness first: allow a very coarse quantizer so the rate control
        // can always hit the frame budget by lowering quality instead of
        // dropping or delaying frames. A blocky frame reads as "low quality";
        // a missing frame reads as "stutter".
        ctx.qmin(4);
        ctx.qmax(60);

        AVDictionary opts = new AVDictionary(null);
        av_dict_set(opts, "deadline", "realtime", 0);
        av_dict_set(opts, "lag-in-frames", "0", 0);
        // Never let libvpx drop frames internally: dropped frames are exactly
        // the stutter we are trying to avoid. Overly complex scenes degrade to
        // qmax (coarse but smooth) instead.
        av_dict_set(opts, "drop-threshold", "0", 0);

        if (codecType == StreamCodec.VP9) {
            // Realtime VP9 (what WebRTC uses): row multithreading + tiles keep
            // the encode a few ms per frame, speed >= 5 selects the rt path.
            av_dict_set(opts, "row-mt", "1", 0);
            av_dict_set(opts, "tile-columns", "2", 0);
            av_dict_set(opts, "cpu-used", pixels <= 640 * 480 ? "6" : "7", 0);
            // Cyclic refresh: continuously repairs quality where the picture
            // is static without spending a full keyframe.
            av_dict_set(opts, "aq-mode", "3", 0);
        } else {
            // cpu-used tradeoff: 8 = fastest/blurriest, 0 = slowest/sharpest.
            av_dict_set(opts, "cpu-used", pixels <= 640 * 480 ? "5" : (pixels <= 1280 * 720 ? "6" : "8"), 0);
        }

        int err = avcodec_open2(ctx, codec, opts);
        av_dict_free(opts);
        if (err < 0) {
            close();
            throw new IllegalStateException("avcodec_open2 failed: " + err);
        }

        frame = av_frame_alloc();
        frame.format(AV_PIX_FMT_YUV420P);
        frame.width(this.width);
        frame.height(this.height);
        if (av_frame_get_buffer(frame, 0) < 0) {
            close();
            throw new IllegalStateException("av_frame_get_buffer failed");
        }

        packet = av_packet_alloc();
    }

    public StreamCodec getCodec() {
        return codecType;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * Encodes one BGRA frame (must be a direct ByteBuffer, tightly packed, srcW*srcH*4 bytes).
     */
    public List<EncodedFrame> encode(ByteBuffer bgra, int srcW, int srcH, boolean forceKeyframe) {
        List<EncodedFrame> out = new ArrayList<>(1);

        if (sws == null || swsSrcW != srcW || swsSrcH != srcH) {
            if (sws != null)
                sws_freeContext(sws);
            // Accurate rounding + full chroma interpolation: noticeably cleaner
            // colored text/UI edges for the (tiny) extra cost of a better
            // BGRA -> YUV420 conversion.
            sws = sws_getContext(srcW, srcH, AV_PIX_FMT_BGRA, width, height, AV_PIX_FMT_YUV420P,
                    SWS_BILINEAR | SWS_ACCURATE_RND | SWS_FULL_CHR_H_INT, null, null, (DoublePointer) null);
            if (sws == null)
                throw new IllegalStateException("sws_getContext failed");
            swsSrcW = srcW;
            swsSrcH = srcH;

            // (Re)allocate the BGRA staging frame; av_frame_get_buffer gives us the
            // alignment + padding swscale's SIMD paths require (a tightly-packed
            // ByteBuffer would crash with an out-of-bounds read).
            if (srcFrame != null)
                av_frame_free(srcFrame);
            srcFrame = av_frame_alloc();
            srcFrame.format(AV_PIX_FMT_BGRA);
            srcFrame.width(srcW);
            srcFrame.height(srcH);
            if (av_frame_get_buffer(srcFrame, 32) < 0)
                throw new IllegalStateException("av_frame_get_buffer (src) failed");
        }

        // Copy the incoming pixels into the staging frame, row by row if needed
        bgra.position(0);
        try (BytePointer src = new BytePointer(bgra)) {
            BytePointer dst = srcFrame.data(0);
            int dstStride = srcFrame.linesize(0);
            int rowBytes = srcW * 4;
            if (dstStride == rowBytes) {
                Pointer.memcpy(dst, src, (long) rowBytes * srcH);
            } else {
                for (int y = 0; y < srcH; y++)
                    Pointer.memcpy(dst.getPointer((long) y * dstStride), src.getPointer((long) y * rowBytes), rowBytes);
            }
        }

        av_frame_make_writable(frame);
        sws_scale(sws, srcFrame.data(), srcFrame.linesize(), 0, srcH, frame.data(), frame.linesize());

        frame.pts(pts++);
        if (forceKeyframe) {
            frame.pict_type(AV_PICTURE_TYPE_I);
            frame.key_frame(1);
        } else {
            frame.pict_type(AV_PICTURE_TYPE_NONE);
            frame.key_frame(0);
        }

        if (avcodec_send_frame(ctx, frame) < 0)
            return out;

        while (avcodec_receive_packet(ctx, packet) == 0) {
            byte[] bytes = new byte[packet.size()];
            packet.data().position(0).get(bytes);
            out.add(new EncodedFrame(bytes, (packet.flags() & AV_PKT_FLAG_KEY) != 0));
            av_packet_unref(packet);
        }

        return out;
    }

    @Override
    public void close() {
        if (sws != null) {
            sws_freeContext(sws);
            sws = null;
        }
        if (packet != null) {
            av_packet_free(packet);
            packet = null;
        }
        if (frame != null) {
            av_frame_free(frame);
            frame = null;
        }
        if (srcFrame != null) {
            av_frame_free(srcFrame);
            srcFrame = null;
        }
        if (ctx != null) {
            avcodec_free_context(ctx);
            ctx = null;
        }
    }
}
