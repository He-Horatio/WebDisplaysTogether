package net.montoyo.wd.client.stream;

import com.cinemamod.mcef.MCEFBrowser;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.core.IScreenStream;
import net.montoyo.wd.utilities.browser.InWorldQueries;
import net.montoyo.wd.utilities.browser.WDBrowser;
import org.cef.browser.CefBrowser;

/**
 * Legacy "local browsing" path: every client runs its own MCEF browser for the
 * screen, exactly like the original WebDisplays. Wrapped as an
 * {@link IScreenStream} so the renderer does not care where frames come from.
 */
public class LocalScreenStream implements IScreenStream {

    private final CefBrowser browser;
    private boolean closed = false;

    public LocalScreenStream(ScreenBlockEntity be, ScreenData scr) {
        String url = WebDisplays.applyBlacklist(scr.url != null ? scr.url : "https://www.google.com");
        browser = WDBrowser.createBrowser(url, false);

        if (browser instanceof MCEFBrowser mcefBrowser) {
            if (scr.rotation != null && scr.rotation.isVertical)
                mcefBrowser.resize(scr.resolution.y, scr.resolution.x);
            else
                mcefBrowser.resize(scr.resolution.x, scr.resolution.y);

            mcefBrowser.setCursorChangeListener((type) -> scr.mouseType = type);
        }

        if (browser instanceof WDBrowser wdBrowser)
            InWorldQueries.attach(be, scr.side, wdBrowser);
    }

    public MCEFBrowser getBrowser() {
        return (browser instanceof MCEFBrowser mcefBrowser) ? mcefBrowser : null;
    }

    public void loadURL(String url) {
        if (!closed)
            browser.loadURL(url);
    }

    public void resize(ScreenData scr) {
        if (!closed && browser instanceof MCEFBrowser mcefBrowser) {
            if (scr.rotation != null && scr.rotation.isVertical)
                mcefBrowser.resize(scr.resolution.y, scr.resolution.x);
            else
                mcefBrowser.resize(scr.resolution.x, scr.resolution.y);
        }
    }

    @Override
    public int pollTextureId() {
        if (closed || !(browser instanceof MCEFBrowser mcefBrowser))
            return 0;

        return mcefBrowser.getRenderer().getTextureID();
    }

    @Override
    public boolean hasFrame() {
        return !closed;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            browser.close(true);
        }
    }
}
