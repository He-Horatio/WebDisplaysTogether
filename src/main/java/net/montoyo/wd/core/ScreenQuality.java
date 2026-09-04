package net.montoyo.wd.core;

/**
 * Per-screen stream quality selected in the screen configurator. The server
 * browser always renders at the screen's own resolution (up to 1080p); this
 * setting only limits the resolution of the encoded video stream.
 */
public enum ScreenQuality {

    /** Adaptive 360p-1080p (AIMD on encoder load and viewer feedback). */
    AUTO(0),

    /** "Fluent": fixed 360p stream. */
    FLUENT(360),

    /** "SD": fixed 720p stream. */
    SD(720),

    /** "HD": fixed 1080p stream. */
    HD(1080);

    /** Stream height in pixels; 0 = automatic. */
    public final int height;

    ScreenQuality(int height) {
        this.height = height;
    }

    public static ScreenQuality of(int ordinal) {
        ScreenQuality[] values = values();
        return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : AUTO;
    }

}
