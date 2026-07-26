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

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * VP8/VP9 decoder producing BGRA frames ready for GL upload.
 * Not thread-safe; use from a single thread (the decode thread).
 */
public final class VideoDecoder implements AutoCloseable {
    /** Decoded BGRA frame; buffer is reused across decode calls. */
    public static final class DecodedFrame {
        public ByteBuffer buffer;
        public int width, height;
    }

    private final StreamCodec codecType;
    private AVCodecContext ctx;
    private AVFrame frame;
    private AVFrame bgraFrame; // aligned+padded BGRA staging frame for swscale
    private AVPacket packet;
    private SwsContext sws;
    private int outW = -1, outH = -1;
    private final DecodedFrame decoded = new DecodedFrame();

    public VideoDecoder(StreamCodec codecType) {
        this.codecType = codecType;

        AVCodec codec = avcodec_find_decoder(codecType.avCodecId);
        if (codec == null)
            throw new IllegalStateException(codecType + " decoder not available");

        ctx = avcodec_alloc_context3(codec);
        if (ctx == null)
            throw new IllegalStateException("Could not allocate decoder context");

        if (avcodec_open2(ctx, codec, (AVDictionary) null) < 0) {
            close();
            throw new IllegalStateException("avcodec_open2 failed");
        }

        frame = av_frame_alloc();
        packet = av_packet_alloc();
    }

    public StreamCodec getCodec() {
        return codecType;
    }

    /**
     * Decodes one packet. Returns the decoded BGRA frame, or null if the
     * decoder produced no output (corrupt data / waiting for keyframe).
     */
    public DecodedFrame decode(byte[] data) {
        if (av_new_packet(packet, data.length) < 0)
            return null;

        packet.data().position(0).put(data);

        int err = avcodec_send_packet(ctx, packet);
        av_packet_unref(packet);
        if (err < 0)
            return null;

        if (avcodec_receive_frame(ctx, frame) != 0)
            return null;

        int w = frame.width();
        int h = frame.height();
        if (w <= 0 || h <= 0)
            return null;

        if (sws == null || w != outW || h != outH) {
            if (sws != null)
                sws_freeContext(sws);
            sws = sws_getContext(w, h, frame.format(), w, h, AV_PIX_FMT_BGRA,
                    SWS_BILINEAR, null, null, (DoublePointer) null);
            if (sws == null)
                return null;
            outW = w;
            outH = h;
            decoded.buffer = ByteBuffer.allocateDirect(w * h * 4);

            // Staging frame: swscale's SIMD paths need aligned + padded output,
            // writing straight into a tightly-packed ByteBuffer can crash.
            if (bgraFrame != null)
                av_frame_free(bgraFrame);
            bgraFrame = av_frame_alloc();
            bgraFrame.format(AV_PIX_FMT_BGRA);
            bgraFrame.width(w);
            bgraFrame.height(h);
            if (av_frame_get_buffer(bgraFrame, 32) < 0)
                return null;
        }

        sws_scale(sws, frame.data(), frame.linesize(), 0, h, bgraFrame.data(), bgraFrame.linesize());

        // Copy the staging frame into the tightly-packed output buffer
        BytePointer src = bgraFrame.data(0);
        int srcStride = bgraFrame.linesize(0);
        int rowBytes = w * 4;
        decoded.buffer.position(0);
        try (BytePointer dst = new BytePointer(decoded.buffer)) {
            if (srcStride == rowBytes) {
                Pointer.memcpy(dst, src, (long) rowBytes * h);
            } else {
                for (int y = 0; y < h; y++)
                    Pointer.memcpy(dst.getPointer((long) y * rowBytes), src.getPointer((long) y * srcStride), rowBytes);
            }
        }

        decoded.buffer.position(0);
        decoded.width = w;
        decoded.height = h;
        return decoded;
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
        if (bgraFrame != null) {
            av_frame_free(bgraFrame);
            bgraFrame = null;
        }
        if (ctx != null) {
            avcodec_free_context(ctx);
            ctx = null;
        }
    }
}
