package net.montoyo.wd.serverbrowser;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefRequestContext;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;

import java.awt.Rectangle;
import java.nio.ByteBuffer;

/**
 * A headless off-screen Chromium browser running on the logical server.
 * Captures BGRA frames from CEF's onPaint into a stable double buffer that
 * the encoder thread (or the local single-player renderer) can copy from.
 *
 * Input injection mirrors MCEF's MCEFBrowser (including the middle/right
 * button swap) so behavior matches the old client-side browsers exactly.
 */
public class ServerScreenBrowser extends CefBrowserOsr {
    private final Object frameLock = new Object();
    private ByteBuffer frame; // full BGRA frame, tightly packed
    private int frameWidth, frameHeight;
    private long frameCounter;
    private volatile long lastPaintMs; // wall clock of the last onPaint, for liveness checks
    private int btnMask = 0;

    // GLFW_PRESS / GLFW_RELEASE values; literals so the dedicated server never touches LWJGL
    private static final int EVT_PRESS = 1;
    private static final int EVT_RELEASE = 0;

    public ServerScreenBrowser(CefClient client, String url, int width, int height, CefRequestContext context) {
        super(client, url, false, context);
        browser_rect_.setBounds(0, 0, Math.max(1, width), Math.max(1, height));
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        if (popup || dirtyRects.length == 0)
            return;

        synchronized (frameLock) {
            int size = width * height * 4;
            if (frame == null || frameWidth != width || frameHeight != height) {
                frame = ByteBuffer.allocateDirect(size);
                frameWidth = width;
                frameHeight = height;
            }

            ByteBuffer src = buffer.duplicate();
            src.position(0).limit(size);
            frame.clear();
            frame.put(src);
            frame.flip();
            frameCounter++;
        }
        lastPaintMs = System.currentTimeMillis();
    }

    /** Wall-clock time of the last paint delivered by the render process (0 if none yet). */
    public long getLastPaintMs() {
        return lastPaintMs;
    }

    /**
     * Asks CEF to repaint the whole view. A live render process answers with an
     * onPaint within milliseconds (even for fully static pages), so this doubles
     * as a cheap liveness probe: no paint after this call means the render
     * process is dead or hung.
     */
    public void requestRepaint() {
        invalidate();
    }

    /** Monotonic counter incremented for every painted frame. */
    public long getFrameCounter() {
        synchronized (frameLock) {
            return frameCounter;
        }
    }

    /**
     * Copies the latest frame into {@code dst} (reallocating if needed).
     * Returns null if no frame is available or nothing changed since {@code lastCounter}.
     */
    public FrameCopy copyFrame(FrameCopy dst, long lastCounter) {
        synchronized (frameLock) {
            if (frame == null || frameCounter == lastCounter)
                return null;

            int size = frameWidth * frameHeight * 4;
            if (dst == null || dst.buffer.capacity() < size)
                dst = new FrameCopy(ByteBuffer.allocateDirect(size));

            ByteBuffer src = frame.duplicate();
            src.position(0).limit(size);
            dst.buffer.clear();
            dst.buffer.put(src);
            dst.buffer.flip();
            dst.width = frameWidth;
            dst.height = frameHeight;
            dst.counter = frameCounter;
            return dst;
        }
    }

    public static final class FrameCopy {
        public final ByteBuffer buffer;
        public int width, height;
        public long counter;

        public FrameCopy(ByteBuffer buffer) {
            this.buffer = buffer;
        }
    }

    public void resize(int width, int height) {
        browser_rect_.setBounds(0, 0, Math.max(1, width), Math.max(1, height));
        wasResized(Math.max(1, width), Math.max(1, height));
    }

    // ------------------------------------------------------------------
    // Input injection (ported from com.cinemamod.mcef.MCEFBrowser)
    // ------------------------------------------------------------------

    public void sendKeyPress(int keyCode, long scanCode, int modifiers) {
        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_PRESS, keyCode, (char) keyCode, modifiers);
        e.scancode = scanCode;
        sendKeyEvent(e);
    }

    public void sendKeyRelease(int keyCode, long scanCode, int modifiers) {
        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_RELEASE, keyCode, (char) keyCode, modifiers);
        e.scancode = scanCode;
        sendKeyEvent(e);
    }

    public void sendKeyTyped(char c, int modifiers) {
        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_TYPE, c, c, modifiers);
        sendKeyEvent(e);
    }

    public void sendMouseMove(int mouseX, int mouseY) {
        CefMouseEvent e = new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, mouseX, mouseY, 0, 0, btnMask);
        sendMouseEvent(e);
    }

    public void sendMousePress(int mouseX, int mouseY, int button) {
        // for some reason, middle and right are swapped in MC (see MCEFBrowser)
        if (button == 1) button = 2;
        else if (button == 2) button = 1;

        if (button == 0) btnMask |= CefMouseEvent.BUTTON1_MASK;
        else if (button == 1) btnMask |= CefMouseEvent.BUTTON2_MASK;
        else if (button == 2) btnMask |= CefMouseEvent.BUTTON3_MASK;

        CefMouseEvent e = new CefMouseEvent(EVT_PRESS, mouseX, mouseY, 1, button, btnMask);
        sendMouseEvent(e);
    }

    public void sendMouseRelease(int mouseX, int mouseY, int button) {
        if (button == 1) button = 2;
        else if (button == 2) button = 1;

        if (button == 0 && (btnMask & CefMouseEvent.BUTTON1_MASK) != 0) btnMask ^= CefMouseEvent.BUTTON1_MASK;
        else if (button == 1 && (btnMask & CefMouseEvent.BUTTON2_MASK) != 0) btnMask ^= CefMouseEvent.BUTTON2_MASK;
        else if (button == 2 && (btnMask & CefMouseEvent.BUTTON3_MASK) != 0) btnMask ^= CefMouseEvent.BUTTON3_MASK;

        CefMouseEvent e = new CefMouseEvent(EVT_RELEASE, mouseX, mouseY, 1, button, btnMask);
        sendMouseEvent(e);
    }

    public void sendMouseWheel(int mouseX, int mouseY, double amount, int modifiers) {
        CefMouseWheelEvent e = new CefMouseWheelEvent(CefMouseWheelEvent.WHEEL_UNIT_SCROLL, mouseX, mouseY, amount, modifiers);
        sendMouseWheelEvent(e);
    }

    public void closeBrowser() {
        try {
            close(true);
        } catch (Throwable ignored) {
        }
    }
}
