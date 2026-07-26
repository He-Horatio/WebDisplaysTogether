package net.montoyo.wd.client.audio;

import net.montoyo.wd.audio.PcmConverter;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.serverbrowser.ServerBrowserManager;
import net.montoyo.wd.serverbrowser.StreamedScreen;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.browser.WDClientBrowser;
import net.montoyo.wd.utilities.data.BlockSide;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefAudioHandler;
import org.cef.misc.CefAudioParameters;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Audio handler registered on the client's MCEF CefClient. Two capture cases
 * share it (CefClient supports a single audio handler):
 * - server-rendered screen browsers when running the integrated server
 * - local-mode (client-side) screen browsers
 * Minepad and other browsers keep normal system audio output.
 */
public final class IntegratedAudioRouter implements CefAudioHandler {
    public static final IntegratedAudioRouter INSTANCE = new IntegratedAudioRouter();

    /** browser identifier -> {sampleRate, channels} for local-mode browsers */
    private final ConcurrentHashMap<Integer, int[]> localFormats = new ConcurrentHashMap<>();

    private IntegratedAudioRouter() {
    }

    private static boolean isLocalScreenBrowser(CefBrowser browser) {
        return browser instanceof WDClientBrowser wb && wb.getBe() != null && wb.getSide() != null;
    }

    @Override
    public boolean getAudioParameters(CefBrowser browser, CefAudioParameters params) {
        StreamedScreen ss = ServerBrowserManager.findByBrowser(browser);
        if (ss != null) { // integrated server's own browser
            ss.setAudioFormat(params != null ? params.sampleRate : 48000, 2);
            return true;
        }

        if (isLocalScreenBrowser(browser)) {
            localFormats.put(browser.getIdentifier(),
                    new int[]{params != null ? params.sampleRate : 48000, 2});
            return true;
        }

        return false; // minepad & friends: leave system audio alone
    }

    @Override
    public void onAudioStreamStarted(CefBrowser browser, CefAudioParameters params, int channels) {
        // params is null with the MCEF jcef natives; only |channels| is usable
        StreamedScreen ss = ServerBrowserManager.findByBrowser(browser);
        if (ss != null) {
            ss.setAudioChannels(channels);
            return;
        }

        int[] fmt = localFormats.get(browser.getIdentifier());
        if (fmt != null && channels > 0)
            fmt[1] = channels;
    }

    @Override
    public void onAudioStreamPacket(CefBrowser browser, float[] data, int frames, long pts) {
        if (data == null || frames <= 0)
            return;

        StreamedScreen ss = ServerBrowserManager.findByBrowser(browser);
        if (ss != null) {
            ss.pushAudio(data, frames);
            return;
        }

        if (!(browser instanceof WDClientBrowser wb))
            return;

        ScreenBlockEntity be = wb.getBe();
        BlockSide side = wb.getSide();
        if (be == null || side == null)
            return;

        int[] fmt = localFormats.getOrDefault(browser.getIdentifier(), new int[]{48000, 1});
        int ch = (data.length == frames * fmt[1]) ? fmt[1] : 1;
        float[] stereo = PcmConverter.toStereo48k(data, frames, ch, fmt[0]);
        ScreenAudioManager.pushFromLocalBrowser(be.getBlockPos(), side, stereo);
    }

    @Override
    public void onAudioStreamStopped(CefBrowser browser) {
        StreamedScreen ss = ServerBrowserManager.findByBrowser(browser);
        if (ss != null)
            ss.audioStopped();
    }

    @Override
    public void onAudioStreamError(CefBrowser browser, String text) {
        Log.warning("CEF audio stream error (client): %s", text);
    }
}
