package net.montoyo.wd.audio;

/**
 * Small helpers to bring arbitrary CEF PCM (interleaved float, any channel
 * count / sample rate) to the pipeline format: interleaved float stereo 48kHz.
 */
public final class PcmConverter {
    public static final int SAMPLE_RATE = 48000;

    private PcmConverter() {
    }

    /**
     * @param data     interleaved float samples
     * @param frames   number of frames (samples per channel)
     * @param channels channel count of the input
     * @param rate     sample rate of the input
     * @return interleaved stereo float samples at 48kHz
     */
    public static float[] toStereo48k(float[] data, int frames, int channels, int rate) {
        //Channel conversion first
        float[] stereo;
        if (channels == 2) {
            stereo = (data.length == frames * 2) ? data : java.util.Arrays.copyOf(data, frames * 2);
        } else if (channels == 1) {
            stereo = new float[frames * 2];
            for (int i = 0; i < frames; i++) {
                stereo[i * 2] = data[i];
                stereo[i * 2 + 1] = data[i];
            }
        } else {
            //Downmix: first two channels get priority, remaining channels are spread evenly
            stereo = new float[frames * 2];
            for (int i = 0; i < frames; i++) {
                float l = data[i * channels];
                float r = data[i * channels + 1];
                for (int c = 2; c < channels; c++) {
                    float s = data[i * channels + c] * 0.5f;
                    l += s;
                    r += s;
                }
                stereo[i * 2] = l;
                stereo[i * 2 + 1] = r;
            }
        }

        if (rate == SAMPLE_RATE)
            return stereo;

        //Naive linear resampling (CEF virtually always outputs 48kHz, so this is a rare fallback)
        int outFrames = (int) ((long) frames * SAMPLE_RATE / rate);
        float[] out = new float[outFrames * 2];
        double step = ((double) frames) / ((double) outFrames);

        for (int i = 0; i < outFrames; i++) {
            double srcPos = i * step;
            int i0 = (int) srcPos;
            int i1 = Math.min(i0 + 1, frames - 1);
            float t = (float) (srcPos - i0);

            out[i * 2] = stereo[i0 * 2] * (1 - t) + stereo[i1 * 2] * t;
            out[i * 2 + 1] = stereo[i0 * 2 + 1] * (1 - t) + stereo[i1 * 2 + 1] * t;
        }

        return out;
    }

    /** Interleaved stereo float [-1,1] to interleaved stereo signed 16-bit. */
    public static short[] toPcm16(float[] stereo) {
        return toPcm16(stereo, 1.0f);
    }

    /**
     * Interleaved stereo float [-1,1] to interleaved stereo signed 16-bit, with
     * a software gain applied. Used for the screen volume slider, since OpenAL
     * source gains are clamped at 1.0. Gains above 1.0 go through a tanh soft
     * limiter: quiet passages get the full boost while peaks are compressed
     * smoothly toward full scale instead of hard-clipping into distortion.
     */
    public static short[] toPcm16(float[] stereo, float gain) {
        short[] out = new short[stereo.length];

        if (gain <= 1.0f) {
            for (int i = 0; i < stereo.length; i++) {
                float v = stereo[i] * gain;
                if (v > 1.0f) v = 1.0f;
                else if (v < -1.0f) v = -1.0f;
                out[i] = (short) (v * 32767.0f);
            }
        } else {
            for (int i = 0; i < stereo.length; i++) {
                float v = (float) Math.tanh(stereo[i] * gain);
                out[i] = (short) (v * 32767.0f);
            }
        }

        return out;
    }

    /** Downmixes interleaved stereo 16-bit PCM to mono. */
    public static short[] stereoToMono(short[] stereo) {
        short[] mono = new short[stereo.length / 2];
        for (int i = 0; i < mono.length; i++)
            mono[i] = (short) ((stereo[i * 2] + stereo[i * 2 + 1]) / 2);
        return mono;
    }

    /** Weighted mono mix of interleaved stereo 16-bit PCM (for speakers). */
    public static short[] weightedMono(short[] stereo, float leftWeight, float rightWeight) {
        short[] mono = new short[stereo.length / 2];
        for (int i = 0; i < mono.length; i++) {
            float v = stereo[i * 2] * leftWeight + stereo[i * 2 + 1] * rightWeight;
            if (v > 32767f) v = 32767f;
            else if (v < -32768f) v = -32768f;
            mono[i] = (short) v;
        }
        return mono;
    }
}
