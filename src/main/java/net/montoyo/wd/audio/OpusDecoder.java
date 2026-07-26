package net.montoyo.wd.audio;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.ShortPointer;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * Opus decoder for the screen audio stream (client side). Outputs interleaved
 * stereo 48kHz float samples. Not thread-safe; use from a single thread.
 */
public final class OpusDecoder implements AutoCloseable {
    private AVCodecContext ctx;
    private AVFrame frame;
    private AVPacket packet;
    private BytePointer packetData;
    private int packetCapacity = 0;

    public OpusDecoder() {
        AVCodec codec = avcodec_find_decoder_by_name("libopus");
        if (codec == null)
            codec = avcodec_find_decoder(AV_CODEC_ID_OPUS);
        if (codec == null)
            throw new IllegalStateException("No Opus decoder available");

        ctx = avcodec_alloc_context3(codec);
        if (ctx == null)
            throw new IllegalStateException("Could not allocate Opus decoder context");

        ctx.sample_rate(PcmConverter.SAMPLE_RATE);
        av_channel_layout_default(ctx.ch_layout(), 2);

        if (avcodec_open2(ctx, codec, (org.bytedeco.ffmpeg.avutil.AVDictionary) null) < 0) {
            close();
            throw new IllegalStateException("Could not open Opus decoder");
        }

        frame = av_frame_alloc();
        packet = av_packet_alloc();
        packetCapacity = 4096;
        packetData = new BytePointer(av_malloc(packetCapacity + AV_INPUT_BUFFER_PADDING_SIZE)).capacity(packetCapacity);
    }

    /**
     * Decodes one Opus packet into interleaved stereo float samples, or null if
     * the decoder produced nothing.
     */
    public float[] decode(byte[] data) {
        if (ctx == null)
            return null;

        if (data.length > packetCapacity) {
            av_free(packetData);
            packetCapacity = data.length * 2;
            packetData = new BytePointer(av_malloc(packetCapacity + AV_INPUT_BUFFER_PADDING_SIZE)).capacity(packetCapacity);
        }

        packetData.position(0).put(data, 0, data.length);
        av_packet_unref(packet);
        packet.data(packetData);
        packet.size(data.length);

        if (avcodec_send_packet(ctx, packet) < 0)
            return null;

        float[] result = null;
        while (avcodec_receive_frame(ctx, frame) == 0) {
            float[] pcm = frameToStereoFloat();
            if (pcm != null)
                result = (result == null) ? pcm : concat(result, pcm);
        }

        return result;
    }

    private float[] frameToStereoFloat() {
        int samples = frame.nb_samples();
        int channels = frame.ch_layout().nb_channels();
        if (samples <= 0 || channels <= 0)
            return null;

        int fmt = frame.format();
        float[] interleaved;

        if (fmt == AV_SAMPLE_FMT_FLT) {
            interleaved = new float[samples * channels];
            new FloatPointer(frame.data(0)).get(interleaved);
        } else if (fmt == AV_SAMPLE_FMT_FLTP) {
            interleaved = new float[samples * channels];
            for (int c = 0; c < channels; c++) {
                FloatPointer plane = new FloatPointer(frame.data(c));
                for (int i = 0; i < samples; i++)
                    interleaved[i * channels + c] = plane.get(i);
            }
        } else if (fmt == AV_SAMPLE_FMT_S16) {
            interleaved = new float[samples * channels];
            ShortPointer p = new ShortPointer(frame.data(0));
            for (int i = 0; i < interleaved.length; i++)
                interleaved[i] = p.get(i) / 32768.0f;
        } else if (fmt == AV_SAMPLE_FMT_S16P) {
            interleaved = new float[samples * channels];
            for (int c = 0; c < channels; c++) {
                ShortPointer plane = new ShortPointer(frame.data(c));
                for (int i = 0; i < samples; i++)
                    interleaved[i * channels + c] = plane.get(i) / 32768.0f;
            }
        } else {
            return null;
        }

        // pipeline format is stereo 48k; convert if the decoder gave us something else
        return PcmConverter.toStereo48k(interleaved, samples, channels, frame.sample_rate() > 0 ? frame.sample_rate() : PcmConverter.SAMPLE_RATE);
    }

    private static float[] concat(float[] a, float[] b) {
        float[] out = new float[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Override
    public void close() {
        if (packetData != null) {
            av_free(packetData);
            packetData = null;
        }
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
