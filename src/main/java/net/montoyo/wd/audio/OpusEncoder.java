package net.montoyo.wd.audio;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.FloatPointer;

import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * Opus encoder for the screen audio stream. Input must already be interleaved
 * float stereo 48kHz (see {@link PcmConverter}); samples are buffered
 * internally until a full Opus frame is available.
 * Not thread-safe; use from a single thread.
 */
public final class OpusEncoder implements AutoCloseable {
    public static final int SAMPLE_RATE = PcmConverter.SAMPLE_RATE;
    public static final int CHANNELS = 2;

    private AVCodecContext ctx;
    private AVFrame frame;
    private AVPacket packet;
    private final int frameSize; // samples per channel per opus frame
    private long pts = 0;

    // pending interleaved stereo samples not yet forming a full frame
    private float[] fifo = new float[0];
    private int fifoLen = 0;

    public OpusEncoder(int bitrateKbps) {
        AVCodec codec = avcodec_find_encoder_by_name("libopus");
        boolean experimental = false;
        if (codec == null) {
            codec = avcodec_find_encoder(AV_CODEC_ID_OPUS); // ffmpeg native encoder
            experimental = true;
        }
        if (codec == null)
            throw new IllegalStateException("No Opus encoder available");

        ctx = avcodec_alloc_context3(codec);
        if (ctx == null)
            throw new IllegalStateException("Could not allocate Opus encoder context");

        ctx.sample_rate(SAMPLE_RATE);
        av_channel_layout_default(ctx.ch_layout(), CHANNELS);
        ctx.sample_fmt(pickSampleFormat(codec));
        ctx.bit_rate(bitrateKbps * 1000L);
        ctx.time_base().num(1);
        ctx.time_base().den(SAMPLE_RATE);
        if (experimental)
            ctx.strict_std_compliance(FF_COMPLIANCE_EXPERIMENTAL);

        // Tune explicitly for music/web audio rather than trusting defaults:
        // 'audio' mode keeps the full-band MDCT path (the 'voip' mode's
        // speech tuning smears music), max complexity buys quality at
        // negligible CPU (a few % of one core), and constrained VBR keeps
        // packets near the target size so the network pacing stays smooth.
        org.bytedeco.ffmpeg.avutil.AVDictionary opts = new org.bytedeco.ffmpeg.avutil.AVDictionary(null);
        av_dict_set(opts, "application", "audio", 0);
        av_dict_set(opts, "compression_level", "10", 0);
        av_dict_set(opts, "vbr", "constrained", 0);
        av_dict_set(opts, "frame_duration", "20", 0);

        int openErr = avcodec_open2(ctx, codec, opts);
        av_dict_free(opts);
        if (openErr < 0) {
            close();
            throw new IllegalStateException("Could not open Opus encoder");
        }

        frameSize = ctx.frame_size() > 0 ? ctx.frame_size() : 960;

        frame = av_frame_alloc();
        frame.format(ctx.sample_fmt());
        frame.sample_rate(SAMPLE_RATE);
        av_channel_layout_default(frame.ch_layout(), CHANNELS);
        frame.nb_samples(frameSize);
        if (av_frame_get_buffer(frame, 0) < 0) {
            close();
            throw new IllegalStateException("av_frame_get_buffer (opus) failed");
        }

        packet = av_packet_alloc();
    }

    private static int pickSampleFormat(AVCodec codec) {
        // libopus prefers interleaved float; native encoder wants FLT too
        var fmts = codec.sample_fmts();
        if (fmts != null) {
            for (int i = 0; ; i++) {
                int fmt = fmts.get(i);
                if (fmt == -1)
                    break;
                if (fmt == AV_SAMPLE_FMT_FLT)
                    return AV_SAMPLE_FMT_FLT;
            }
            return fmts.get(0);
        }
        return AV_SAMPLE_FMT_FLT;
    }

    /**
     * Feeds interleaved stereo 48kHz float samples and returns any completed
     * Opus packets.
     */
    public List<byte[]> encode(float[] stereo) {
        // append to fifo
        if (fifo.length < fifoLen + stereo.length) {
            float[] bigger = new float[Math.max(fifoLen + stereo.length, fifo.length * 2)];
            System.arraycopy(fifo, 0, bigger, 0, fifoLen);
            fifo = bigger;
        }
        System.arraycopy(stereo, 0, fifo, fifoLen, stereo.length);
        fifoLen += stereo.length;

        List<byte[]> out = new ArrayList<>(2);
        int samplesPerFrame = frameSize * CHANNELS;

        int off = 0;
        while (fifoLen - off >= samplesPerFrame) {
            encodeOne(fifo, off, out);
            off += samplesPerFrame;
        }

        if (off > 0) {
            System.arraycopy(fifo, off, fifo, 0, fifoLen - off);
            fifoLen -= off;
        }

        return out;
    }

    private void encodeOne(float[] samples, int off, List<byte[]> out) {
        av_frame_make_writable(frame);

        if (frame.format() == AV_SAMPLE_FMT_FLT) {
            new FloatPointer(frame.data(0)).put(samples, off, frameSize * CHANNELS);
        } else { // planar float fallback
            FloatPointer left = new FloatPointer(frame.data(0));
            FloatPointer right = new FloatPointer(frame.data(1));
            for (int i = 0; i < frameSize; i++) {
                left.put(i, samples[off + i * 2]);
                right.put(i, samples[off + i * 2 + 1]);
            }
        }

        frame.pts(pts);
        pts += frameSize;

        if (avcodec_send_frame(ctx, frame) < 0)
            return;

        while (avcodec_receive_packet(ctx, packet) == 0) {
            byte[] bytes = new byte[packet.size()];
            packet.data().position(0).get(bytes);
            out.add(bytes);
            av_packet_unref(packet);
        }
    }

    @Override
    public void close() {
        if (packet != null) {
            av_packet_free(packet);
            packet = null;
        }
        if (frame != null) {
            av_frame_free(frame);
            frame = null;
        }
        if (ctx != null) {
            avcodec_free_context(ctx);
            ctx = null;
        }
    }
}
