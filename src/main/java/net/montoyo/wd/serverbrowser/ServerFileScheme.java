package net.montoyo.wd.serverbrowser;

import net.montoyo.wd.client.WDScheme;
import net.montoyo.wd.miniserv.server.Server;
import net.montoyo.wd.utilities.serialization.Util;
import org.cef.callback.CefCallback;
import org.cef.handler.CefResourceHandler;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Dedicated-server implementation of the {@code webdisplaystogether://} (miniserv) scheme.
 * The server hosts the files itself, so we read them straight from disk
 * instead of doing a miniserv network round-trip.
 */
public class ServerFileScheme implements CefResourceHandler {
    private static final String ERROR_PAGE = "<!DOCTYPE html><html><head></head><body><h1>%d %s</h1><hr /><i>Miniserv powered by WebDisplaysTogether</i></body></html>";

    private final String url;
    private InputStream is = null;
    private String mime = null;
    private byte[] errorData = null;
    private int errorOffset = 0;

    public ServerFileScheme(String url) {
        this.url = url;
    }

    @Override
    public boolean processRequest(CefRequest cefRequest, CefCallback cefCallback) {
        String url = this.url.substring("webdisplaystogether://".length());

        int pos = url.indexOf('/');
        if (pos < 0)
            return false;

        String uuidStr = url.substring(0, pos);
        String fileStr = URLDecoder.decode(url.substring(pos + 1), StandardCharsets.UTF_8);

        if (!uuidStr.isEmpty() && !Util.isFileNameInvalid(fileStr)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                File dir = Server.getInstance().getDirectory();

                if (dir != null) {
                    File file = new File(new File(dir, uuid.toString()), fileStr);
                    if (file.isFile()) {
                        is = new FileInputStream(file);

                        int extPos = fileStr.lastIndexOf('.');
                        if (extPos >= 0)
                            mime = WDScheme.mapMime(fileStr.substring(extPos + 1));
                    }
                }
            } catch (IllegalArgumentException | IOException ignored) {
            }
        }

        if (is == null)
            errorData = String.format(ERROR_PAGE, 404, "Not Found").getBytes(StandardCharsets.UTF_8);

        cefCallback.Continue();
        return true;
    }

    @Override
    public void getResponseHeaders(CefResponse cefResponse, IntRef contentLength, StringRef redir) {
        cefResponse.setStatus(200);
        cefResponse.setStatusText("OK");

        if (errorData != null)
            cefResponse.setMimeType("text/html");
        else if (mime != null)
            cefResponse.setMimeType(mime);

        contentLength.set(0);
    }

    @Override
    public boolean readResponse(byte[] output, int bytesToRead, IntRef bytesRead, CefCallback cefCallback) {
        if (errorData != null) {
            int remaining = errorData.length - errorOffset;
            if (remaining <= 0) {
                bytesRead.set(0);
                return false;
            }
            int toWrite = Math.min(remaining, bytesToRead);
            System.arraycopy(errorData, errorOffset, output, 0, toWrite);
            errorOffset += toWrite;
            bytesRead.set(toWrite);
            return true;
        }

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
