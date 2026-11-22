package com.xlox.utils;

import java.net.URI;
import java.net.URISyntaxException;

/** URL decisions only; no provider tokens are parsed, logged, or persisted. */
public final class GameWebPolicy {
    public static final String ACTION = "com.xlox.action.GAME_WEB_LOGIN";

    private GameWebPolicy() { }

    /** Only pass GAME as verifiedVirtualPackage after resolving the actual virtual activity. */
    public static boolean contain(String action, String url, String verifiedVirtualPackage) {
        return GameLoginRouter.VIEW.equals(action)
                && !GameLoginRouter.GAME.equals(verifiedVirtualPackage) && isAuthenticationEntry(url);
    }

    /** Only authentication entry URLs, not news/store/ordinary game links. Values of tokens are never read. */
    public static boolean isAuthenticationEntry(String value) {
        if (!isHttps(value)) return false;
        URI uri = parse(value);
        String host = uri.getHost().toLowerCase(java.util.Locale.US);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(java.util.Locale.US);
        boolean twitter = "twitter.com".equals(host) || host.endsWith(".twitter.com")
                || "x.com".equals(host) || host.endsWith(".x.com");
        if (twitter && (path.startsWith("/oauth/") || path.startsWith("/i/oauth")
                || path.startsWith("/i/flow/login") || "/login".equals(path))) return true;
        if (path.matches(".*/(?:web[-_]?login|web[-_]?auth|oauth|oauth2)(?:/.*)?")) return true;
        java.util.Set<String> keys = new java.util.HashSet<>();
        String query = uri.getRawQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                String key = part.split("=", 2)[0];
                try {
                    keys.add(java.net.URLDecoder.decode(key, "UTF-8"));
                } catch (java.io.UnsupportedEncodingException | IllegalArgumentException ignored) {
                    return false;
                }
            }
        }
        return keys.contains("oauth_token") || (keys.contains("client_id") && keys.contains("redirect_uri")
                && (keys.contains("response_type") || keys.contains("state")));
    }

    public static boolean isHttps(String value) {
        URI uri = parse(value);
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && !uri.getHost().isEmpty() && uri.getRawUserInfo() == null && !uri.isOpaque();
    }

    /** A display origin, never the secret-bearing path, query, fragment or userinfo. */
    public static String origin(String value) {
        URI uri = parse(value);
        if (!isHttps(value)) return "";
        return "https://" + uri.getHost() + (uri.getPort() < 0 || uri.getPort() == 443 ? "" : ":" + uri.getPort());
    }

    public static boolean mayBeCallback(String value) {
        URI uri = parse(value);
        if (uri == null || uri.getScheme() == null || uri.getRawUserInfo() != null) return false;
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.US);
        // HTTPS App Links may also be resolved against the virtual game. Never
        // hand arbitrary local/JavaScript URLs to a game activity.
        return !("javascript".equals(scheme) || "data".equals(scheme) || "file".equals(scheme)
                || "content".equals(scheme) || "about".equals(scheme) || "blob".equals(scheme)
                || "intent".equals(scheme));
    }

    private static URI parse(String value) {
        if (value == null || value.length() > 32 * 1024) return null;
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
