package net.montoyo.wd.serverbrowser;

import net.montoyo.wd.utilities.Log;
import org.cef.callback.CefCallback;
import org.cef.handler.CefResourceHandler;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Dedicated-server implementation of the {@code mod://} scheme
 * (same URL mapping as MCEF's ModScheme: mod://modid/file -> assets/modid/html/file).
 */
public class ServerModScheme implements CefResourceHandler {
    private final String url;
    private String contentType = null;
    private InputStream is = null;

    public ServerModScheme(String url) {
        this.url = url;
    }

    @Override
    public boolean processRequest(CefRequest cefRequest, CefCallback cefCallback) {
        String url = this.url.substring("mod://".length());

        int pos = url.indexOf('/');
        if (pos < 0) {
            cefCallback.cancel();
            return false;
        }

        String mod = removeSlashes(url.substring(0, pos));
        String loc = removeSlashes(url.substring(pos + 1));

        if (mod.isEmpty() || loc.isEmpty() || mod.charAt(0) == '.' || loc.charAt(0) == '.') {
            Log.warning("Invalid mod:// URL %s", url);
            cefCallback.cancel();
            return false;
        }

        String path = "/assets/" + mod.toLowerCase(Locale.US) + "/html/" + loc.toLowerCase(Locale.US);
        is = ServerModScheme.class.getResourceAsStream(path);
        if (is == null)
            is = ServerModScheme.class.getClassLoader().getResourceAsStream(path.substring(1));

        if (is == null) {
            Log.warning("Resource %s NOT found!", url);
            cefCallback.cancel();
            return false;
        }

        contentType = null;
        pos = loc.lastIndexOf('.');
        if (pos >= 0 && pos < loc.length() - 2)
            contentType = net.montoyo.wd.client.WDScheme.mapMime(loc.substring(pos + 1));

        cefCallback.Continue();
        return true;
    }

    private static String removeSlashes(String loc) {
        int i = 0;
        while (i < loc.length() && loc.charAt(i) == '/')
            i++;
        return loc.substring(i);
    }

    @Override
    public void getResponseHeaders(CefResponse cefResponse, IntRef contentLength, StringRef redir) {
        if (contentType != null)
            cefResponse.setMimeType(contentType);

        cefResponse.setStatus(200);
        cefResponse.setStatusText("OK");
        contentLength.set(0);
    }

    @Override
    public boolean readResponse(byte[] output, int bytesToRead, IntRef bytesRead, CefCallback cefCallback) {
        try {
            int ret = is.read(output, 0, bytesToRead);
            if (ret <= 0) {
                is.close();
                bytesRead.set(0);
                return false;
            }
            bytesRead.set(ret);
            return true;
        } catch (IOException e) {
            try {
                is.close();
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    @Override
    public void cancel() {
        try {
            if (is != null)
                is.close();
        } catch (Throwable ignored) {
        }
    }
}
