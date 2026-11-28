package com.xlox.utils;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Routes a provider's return URI into the running virtual BGMI, never the
 * device's separately installed game. The game's own OAuth SDK still validates
 * its pending request/state; this bridge does not authenticate or read tokens.
 */
public final class GameLoginRouter {
    public static final String GAME = "com.pubg.imobile";
    public static final String USER = "0"; // The existing loader launches the default container user.
    public static final String VIEW = "android.intent.action.VIEW";

    public enum Result { FORWARDED, LEGACY, IGNORED, NO_RUNNING_GAME, NO_HANDLER, AMBIGUOUS_GAME }

    public static final class Target {
        public final String packageName;
        public final String className;
        public final boolean enabled;
        public final boolean exported;

        public Target(String packageName, String className, boolean enabled, boolean exported) {
            this.packageName = packageName;
            this.className = className;
            this.enabled = enabled;
            this.exported = exported;
        }
    }

    public interface Container {
        boolean isRunning();
        /** A bare external callback has no trustworthy package/transaction binding. */
        default boolean isOtherGameRunning() { return false; }
        Target resolve(String uri);
        void launch(Target target, String uri);
    }

    private GameLoginRouter() { }

    public static Result route(String action, String value, Container container) {
        if (!VIEW.equals(action) || value == null || value.length() > 16 * 1024) return Result.IGNORED;
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return Result.IGNORED;
        }
        String scheme = uri.getScheme();
        if ("fbconnect".equals(scheme)) return Result.LEGACY;
        if (!("imobile1450".equals(scheme) || "tencent1106545419".equals(scheme)
                || "twittersdk".equals(scheme))) return Result.IGNORED;
        if (uri.isOpaque() || uri.getRawAuthority() == null || uri.getRawUserInfo() != null) {
            return Result.IGNORED;
        }
        if (!container.isRunning()) return Result.NO_RUNNING_GAME;
        // Air's OAuth contracts are not guessed from BGMI's. If both run,
        // reject this unbound legacy return rather than send Air data to BGMI.
        // BGMI's private, explicitly scoped WebView return path is unaffected.
        if (container.isOtherGameRunning()) return Result.AMBIGUOUS_GAME;
        Target target = container.resolve(value);
        if (target == null || !GAME.equals(target.packageName) || target.className == null
                || target.className.isEmpty() || !target.enabled || !target.exported) {
            return Result.NO_HANDLER;
        }
        container.launch(target, value);
        return Result.FORWARDED;
    }
}
