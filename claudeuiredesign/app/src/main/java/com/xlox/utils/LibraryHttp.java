package com.xlox.utils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;

/** Small HTTPS transport shared by library metadata/download requests. */
public final class LibraryHttp {
    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_MS = 8_000;
    private static final int READ_MS = 12_000;

    private LibraryHttp() { }

    public static HttpURLConnection open(String start, String accept, String userAgent,
                                         boolean fresh, UpdateRequest request) throws IOException {
        URL url = new URL(start);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            request.check();
            // A redirect must not downgrade a verified HTTPS request to HTTP,
            // file://, or another local/custom protocol.
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null) {
                throw new ProtocolException("Library requests require HTTPS");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            boolean handedOff = false;
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(request.timeout(CONNECT_MS));
                connection.setReadTimeout(request.timeout(READ_MS));
                connection.setUseCaches(!fresh);
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setRequestProperty("Accept", accept);
                connection.setRequestProperty("Accept-Encoding", "identity");
                // Metadata/unchecked links must be fresh. A pinned binary may use its
                // Storage/CDN cache: forcing no-cache on every hop made cold
                // downloads needlessly hit the origin again.
                if (fresh) connection.setRequestProperty("Cache-Control", "no-cache");
                request.attach(connection);
                int code = connection.getResponseCode();
                request.check();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) {
                        throw new ProtocolException("Redirect without location");
                    }
                    url = new URL(url, location);
                } else {
                    handedOff = true;
                    return connection;
                }
            } finally {
                if (!handedOff) {
                    request.detach(connection);
                    connection.disconnect();
                }
            }
        }
        throw new ProtocolException("Too many redirects");
    }
}
