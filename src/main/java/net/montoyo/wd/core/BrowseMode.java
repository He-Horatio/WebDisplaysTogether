package net.montoyo.wd.core;

/**
 * How a screen's web content is rendered.
 */
public enum BrowseMode {

    /** The server runs the browser and streams video frames to the clients (default). */
    SERVER,

    /** Each client runs its own local browser, like the legacy WebDisplays behavior. */
    LOCAL;

    public static BrowseMode of(int ordinal) {
        BrowseMode[] values = values();
        return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : SERVER;
    }

}
