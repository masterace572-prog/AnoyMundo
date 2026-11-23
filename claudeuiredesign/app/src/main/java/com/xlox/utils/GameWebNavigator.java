package com.xlox.utils;

/** Main-frame navigation state machine, tested without WebView or real credentials. */
public final class GameWebNavigator {
    public enum Action { LOAD_WEB, RETURNED_TO_GAME, LOAD_FALLBACK, BLOCK }

    public static final class Decision {
        public final Action action;
        public final String fallback;
        Decision(Action action, String fallback) { this.action = action; this.fallback = fallback; }
    }

    public interface Callbacks {
        /** Resolve/dispatch only inside the virtual game, including intent:// parsing if needed. */
        boolean forwardToGame(String url);
        String httpsFallback(String intentUrl);
    }

    private GameWebNavigator() { }

    public static Decision navigate(String url, Callbacks callbacks) {
        if (url != null && url.length() <= 32 * 1024 && url.startsWith("intent:")) {
            if (callbacks.forwardToGame(url)) return new Decision(Action.RETURNED_TO_GAME, null);
            String fallback = callbacks.httpsFallback(url);
            return GameWebPolicy.isHttps(fallback) ? new Decision(Action.LOAD_FALLBACK, fallback)
                    : new Decision(Action.BLOCK, null);
        }
        if (GameWebPolicy.mayBeCallback(url) && callbacks.forwardToGame(url)) {
            return new Decision(Action.RETURNED_TO_GAME, null);
        }
        return new Decision(GameWebPolicy.isHttps(url) || "about:blank".equals(url)
                ? Action.LOAD_WEB : Action.BLOCK, null);
    }
}
