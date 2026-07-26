package net.montoyo.wd.client.audio;

import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;

import java.util.ArrayDeque;

/**
 * One streaming OpenAL source fed with 48kHz 16-bit PCM. Distance attenuation
 * is computed manually by the caller (rolloff factor is 0), so OpenAL only
 * contributes directional panning for positional (mono) sources.
 *
 * Robustness notes: the AL error state is shared with Minecraft's own sound
 * engine (which runs on another thread), so a stale error can be observed by
 * us and vice versa. We therefore clear the error state before critical
 * operations, never treat a single error as fatal, count consecutive failures
 * instead, and query AL_BUFFERS_QUEUED directly rather than keeping our own
 * drift-prone counter. When the source dies (e.g. the AL context was
 * recreated after an audio device change), {@link #isHealthy()} turns false
 * and the owner recreates the source.
 *
 * All methods must be called from the client main thread.
 */
public final class AlStreamSource {
    /** Cap on queued audio to bound latency (~0.8s at 20ms per Opus packet). */
    private static final int MAX_QUEUED_BUFFERS = 40;
    private static final int MAX_CONSECUTIVE_ERRORS = 10;
    /**
     * Packets accumulated in Java before (re)starting playback (~20ms each).
     * This is the steady-state safety buffer: everything that arrives late in
     * a burst - main-thread scheduling, network jitter, video keyframes
     * hogging the TCP connection - eats into it. Too small and the source
     * underruns repeatedly, and every stop/restart is an audible click; a
     * rapid series of them is the "crackling like bad network" sound.
     * 10 packets = ~200ms, in line with what streaming clients use.
     */
    private static final int PREBUFFER_PACKETS = 10;
    /**
     * After an underrun, restart once this much audio is pending again (or
     * after {@link #RESTART_MAX_WAIT_MS}, whichever comes first). Restarting
     * with just one or two packets - what the old code did after a single
     * tick - underruns again within 40ms and turns one hiccup into a long
     * crackle.
     */
    private static final int RESTART_MIN_PACKETS = 5;
    private static final long RESTART_MAX_WAIT_MS = 250;
    /**
     * Cap on the Java-side pending queue (spillover while playing, or
     * accumulation while stopped); ~0.5s. When it overflows the oldest
     * packets are trimmed, which skips playback forward - the only place
     * audio data is ever discarded.
     */
    private static final int PENDING_MAX = 25;

    private record PendingPcm(short[] pcm, int channels) {
    }

    private final int source;
    private final ArrayDeque<Integer> freeBuffers = new ArrayDeque<>();
    private final ArrayDeque<PendingPcm> pending = new ArrayDeque<>();
    private long pendingSinceMs = 0; // when `pending` last went from empty to non-empty
    private boolean valid;
    private int consecutiveErrors = 0;
    private boolean playedBefore = false;
    /** Audible glitches since the last poll: underrun restarts + trimmed backlog. */
    private int glitches = 0;
    /**
     * Set at every playback discontinuity (restart after underrun, skipped
     * backlog). The next uploaded packet gets a short fade-in: resuming from
     * silence mid-waveform is a step discontinuity, heard as a click/crackle
     * ("noise") even when the stream itself is clean.
     */
    private boolean fadeNextPacket = false;
    private static final int FADE_FRAMES = 240; // 5ms at 48kHz

    public AlStreamSource(boolean relative) {
        AL10.alGetError(); // clear any stale error left by other AL users

        source = AL10.alGenSources();
        if (AL10.alGetError() != AL10.AL_NO_ERROR || !AL10.alIsSource(source)) {
            valid = false;
            return;
        }

        valid = true;
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0.0f); // manual attenuation
        AL10.alSourcef(source, AL10.AL_PITCH, 1.0f);
        AL10.alSourcef(source, AL10.AL_GAIN, 1.0f);
        AL10.alSourcef(source, AL10.AL_MAX_GAIN, 1.0f); // volume boost happens in software, on the PCM

        if (relative) {
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(source, AL10.AL_POSITION, 0, 0, 0);
        }

        AL10.alGetError();
    }

    public boolean isValid() {
        return valid;
    }

    /** False once the source is gone or keeps failing; owner should recreate it. */
    public boolean isHealthy() {
        return valid && AL10.alIsSource(source);
    }

    public void setPosition(Vec3 pos) {
        if (valid)
            AL10.alSource3f(source, AL10.AL_POSITION, (float) pos.x, (float) pos.y, (float) pos.z);
    }

    public void setGain(float gain) {
        if (valid)
            AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0.0f, gain));
    }

    /** Queues interleaved 16-bit PCM at 48kHz (channels = 1 or 2). */
    public void queue(short[] pcm, int channels) {
        if (!valid || pcm.length == 0)
            return;

        AL10.alGetError(); // clear stale errors before we start

        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            noteError();
            return;
        }

        if (state == AL10.AL_PLAYING) {
            reclaimProcessed();

            int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                noteError();
                return;
            }

            // Go through `pending` so ordering is preserved when a burst
            // (e.g. audio backed up behind a video keyframe on the shared
            // TCP connection) overflows the AL queue: the excess waits in
            // `pending` and is fed in as playback frees buffers, instead of
            // being discarded and punching holes into the sound.
            addPending(pcm, channels);
            drainPendingIntoQueue(queued);
            return;
        }

        // Source is INITIAL or STOPPED (startup or underrun). While a source is
        // stopped, OpenAL marks every buffer in its queue - including freshly
        // queued ones - as processed, so queueing directly would just get the
        // data stripped again by the next reclaim and playback would never
        // restart. Instead, accumulate on the Java side and restart in one go.
        addPending(pcm, channels);

        if (pending.size() >= PREBUFFER_PACKETS)
            restartFromPending();
    }

    private void addPending(short[] pcm, int channels) {
        if (pending.isEmpty())
            pendingSinceMs = System.currentTimeMillis();

        pending.addLast(new PendingPcm(pcm, channels));
        while (pending.size() > PENDING_MAX) {
            pending.removeFirst();
            glitches++; // playback skips forward: audible
            fadeNextPacket = true;
        }
    }

    /**
     * Number of audible glitches (underrun restarts, dropped backlog) since
     * the last call. Fed back to the server as a congestion signal.
     */
    public int pollGlitches() {
        int n = glitches;
        glitches = 0;
        return n;
    }

    /** Moves as much pending PCM as fits into the AL queue, oldest first. */
    private void drainPendingIntoQueue(int queued) {
        while (!pending.isEmpty() && queued < MAX_QUEUED_BUFFERS) {
            PendingPcm p = pending.removeFirst();
            if (!uploadAndQueue(p.pcm(), p.channels()))
                return;
            consecutiveErrors = 0;
            queued++;
        }
    }

    /** Restarts playback after an underrun and drains spillover; called periodically by the owner. */
    public void tickKeepAlive() {
        if (!valid || pending.isEmpty())
            return;

        AL10.alGetError();
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        if (AL10.alGetError() != AL10.AL_NO_ERROR)
            return;

        if (state == AL10.AL_PLAYING) {
            // Playing fine but spillover is waiting: top the queue back up.
            reclaimProcessed();
            int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
            if (AL10.alGetError() != AL10.AL_NO_ERROR)
                return;
            drainPendingIntoQueue(queued);
            return;
        }

        // Wait for a decent safety buffer before restarting; a restart with
        // one or two packets just underruns again immediately. The time cap
        // covers trickling streams that never reach the packet threshold.
        if (pending.size() >= RESTART_MIN_PACKETS
                || System.currentTimeMillis() - pendingSinceMs > RESTART_MAX_WAIT_MS)
            restartFromPending();
    }

    /** Flushes the stale (fully processed) queue, uploads pending PCM and plays. */
    private void restartFromPending() {
        reclaimProcessed(); // while stopped, this drains the entire old queue

        fadeNextPacket = true; // resuming from silence: soften the edge
        boolean any = false;
        while (!pending.isEmpty()) {
            PendingPcm p = pending.removeFirst();
            any |= uploadAndQueue(p.pcm(), p.channels());
        }

        if (!any)
            return;

        AL10.alSourcePlay(source);
        if (AL10.alGetError() == AL10.AL_NO_ERROR) {
            consecutiveErrors = 0;
            if (playedBefore)
                glitches++; // a restart after playback = an audible underrun
            playedBefore = true;
        } else {
            noteError();
        }
    }

    /** Uploads one PCM packet into a (pooled) buffer and queues it on the source. */
    private boolean uploadAndQueue(short[] pcm, int channels) {
        if (fadeNextPacket) {
            fadeNextPacket = false;
            pcm = withFadeIn(pcm, channels);
        }

        Integer pooled = freeBuffers.poll();
        int buffer = (pooled != null) ? pooled : AL10.alGenBuffers();
        if (AL10.alGetError() != AL10.AL_NO_ERROR || !AL10.alIsBuffer(buffer)) {
            noteError();
            return false;
        }

        int format = (channels == 2) ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
        AL10.alBufferData(buffer, format, pcm, 48000);
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            freeBuffers.add(buffer); // keep it for later; never delete a buffer that might be in use
            noteError();
            return false;
        }

        AL10.alSourceQueueBuffers(source, buffer);
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            freeBuffers.add(buffer);
            noteError();
            return false;
        }

        return true;
    }

    /** Copy of the packet with a linear ~5ms fade-in (kills discontinuity clicks). */
    private static short[] withFadeIn(short[] pcm, int channels) {
        short[] out = pcm.clone();
        int frames = Math.min(FADE_FRAMES, out.length / channels);
        for (int i = 0; i < frames; i++) {
            float g = (float) i / frames;
            for (int c = 0; c < channels; c++) {
                int idx = i * channels + c;
                out[idx] = (short) (out[idx] * g);
            }
        }
        return out;
    }

    private void noteError() {
        if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS)
            valid = false; // give up; the owner will recreate us
    }

    private void reclaimProcessed() {
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        if (AL10.alGetError() != AL10.AL_NO_ERROR)
            return;

        for (int i = 0; i < processed; i++) {
            int buffer = AL10.alSourceUnqueueBuffers(source);
            if (AL10.alGetError() != AL10.AL_NO_ERROR)
                break;
            freeBuffers.add(buffer);
        }
    }

    public void close() {
        pending.clear();

        if (!valid) {
            freeBuffers.clear();
            return;
        }
        valid = false;

        AL10.alGetError();

        if (!AL10.alIsSource(source)) { // context was recreated; all our handles are gone
            freeBuffers.clear();
            return;
        }

        AL10.alSourceStop(source);

        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        for (int i = 0; i < queued; i++) {
            int buffer = AL10.alSourceUnqueueBuffers(source);
            if (AL10.alGetError() != AL10.AL_NO_ERROR)
                break;
            AL10.alDeleteBuffers(buffer);
        }

        for (int buffer : freeBuffers) {
            if (AL10.alIsBuffer(buffer))
                AL10.alDeleteBuffers(buffer);
        }
        freeBuffers.clear();

        AL10.alDeleteSources(source);
        AL10.alGetError(); // clear any residual error
    }
}
