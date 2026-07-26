package net.montoyo.wd.serverbrowser;

import net.montoyo.wd.utilities.Log;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefAudioHandler;
import org.cef.misc.CefAudioParameters;

/**
 * CefAudioHandler that captures PCM from server-side screen browsers and
 * forwards it to the owning {@link StreamedScreen} for Opus encoding +
 * streaming. Registered on the dedicated server's CefClient; the integrated
 * (client) variant extends this to also capture local-mode browsers.
 *
 * Note: with the MCEF 2.1.1 jcef natives, onAudioStreamPacket only carries the
 * FIRST channel (mono) and onAudioStreamStarted receives null params, so the
 * sample rate is recorded in getAudioParameters.
 */
public class ServerAudioRouter implements CefAudioHandler {
    public static final ServerAudioRouter INSTANCE = new ServerAudioRouter();

    protected ServerAudioRouter() {
    }

    @Override
    public boolean getAudioParameters(CefBrowser browser, CefAudioParameters params) {
        StreamedScreen ss = ServerBrowserManager.findByBrowser(browser);
        if (ss == null)
            return false; // not one of ours: leave audio alone

        ss.setAudioFormat(params != null ? params.sampleRate : 48000, 2);
        return true;
    }

    @Override
    public void onAudioStreamStarted(CefBrowser browser, CefAudioParameters params, int channels) {
        // params is null with the MCEF jcef natives; only |channels| is usable
        StreamedScreen ss = ServerBrowserManager.findByBrowser(browser);
        if (ss != null)
            ss.setAudioChannels(channels);
    }

    @Override
    public void onAudioStreamPacket(CefBrowser browser, float[] data, int frames, long pts) {
        StreamedScreen ss = ServerBrowserManager.findByBrowser(browser);
        if (ss != null)
            ss.pushAudio(data, frames);
    }

    @Override
    public void onAudioStreamStopped(CefBrowser browser) {
        StreamedScreen ss = ServerBrowserManager.findByBrowser(browser);
        if (ss != null)
            ss.audioStopped();
    }

    @Override
    public void onAudioStreamError(CefBrowser browser, String text) {
        Log.warning("CEF audio stream error: %s", text);
    }
}
