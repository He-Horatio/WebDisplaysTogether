package net.montoyo.wd.core;

/**
 * Client-side handle to a server-rendered screen video stream.
 * The implementation lives in client-only code; this interface is safe to
 * reference from common code (ScreenData).
 */
public interface IScreenStream {
    /**
     * Polls for new frames (local direct path in single-player) and returns the
     * OpenGL texture ID holding the latest decoded frame. Must be called from
     * the render thread. Returns 0 if no frame has been received yet.
     */
    int pollTextureId();

    /** True once at least one frame has been displayed. */
    boolean hasFrame();

    /** Unsubscribes from the server stream and frees GL/decoder resources. */
    void close();
}
