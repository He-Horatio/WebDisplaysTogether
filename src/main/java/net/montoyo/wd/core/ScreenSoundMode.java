package net.montoyo.wd.core;

/**
 * How a screen's audio is played back on the client.
 */
public enum ScreenSoundMode {

    /** The screen surface itself is a positional audio source (default). */
    SCREEN,

    /** Audio is played through linked speaker blocks. */
    SPEAKERS,

    /** Non-positional playback: full volume within 16 blocks, then fades out. */
    DIRECT;

    public static ScreenSoundMode of(int ordinal) {
        ScreenSoundMode[] values = values();
        return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : SCREEN;
    }

}
