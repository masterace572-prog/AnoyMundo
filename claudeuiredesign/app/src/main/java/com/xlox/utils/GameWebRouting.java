package com.xlox.utils;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.util.Log;

import com.xlox.BuildConfig;
import com.xlox.GameWebLoginActivity;

import net_62v.external.MetaCore;
import net_62v.external.MetaPackageManager;

/** Installed only in the virtual game's process callback, never in the host UI. */
public final class GameWebRouting {
    private static final String TAG = "GameWebRouting";
    private static final String[] SERVICES = {"activity", "activity_task"};
    // These seven method hashes are handled by the ORIGINAL AAR's activity
    // interpreter. Use the public chaining API, not a patched AAR/reflection hook.
    private static final String[] METHODS = {"startActivity", "startActivityAsUser", "startActivityAndWait",
            "startActivityWithConfig", "startActivityAsCaller", "startActivityWithFeature", "startActivityAsUserWithFeature"};
    private static final java.util.Set<String> installed = new java.util.HashSet<>();
    private static boolean finalized;

    private GameWebRouting() { }

    public static synchronized void install() {
        for (String service : SERVICES) {
            for (String method : METHODS) {
                String key = service + ":" + method;
                if (installed.contains(key)) continue;
                GameWebCallRouter router = new GameWebCallRouter(GameWebRouting::redirect);
                try {
                    if (router.install(handler -> MetaCore.addServiceInterpreter(service, method, handler))) {
                        installed.add(key);
                    }
                } catch (RuntimeException ignored) {
                    // Never log exception text: Android may include Intent data.
                    Log.w(TAG, "A game web routing hook was unavailable");
                }
            }
        }
        Log.i(TAG, "In-container web routing handlers installed: " + installed.size());
    }

    public static synchronized void refreshAfterInitialization() {
        if (finalized) return;
        finalized = true;
        // If SDK initialization replaced an early method table, chain the final
        // one too. At most one extra layer; the private action is never rerouted.
        installed.clear();
        install();
    }

    private static Object redirect(Object argument) {
        if (!(argument instanceof Intent)) return null;
        Intent original = (Intent) argument;
        Intent web = original;
        if (Intent.ACTION_CHOOSER.equals(original.getAction())) {
            try {
                web = original.getParcelableExtra(Intent.EXTRA_INTENT);
            } catch (RuntimeException ignored) {
                return null;
            }
            if (web == null) return null;
        }
        ComponentName component = web.getComponent();
        String targetPackage = component == null ? web.getPackage() : component.getPackageName();
        if (!GameWebPolicy.contain(web.getAction(), web.getDataString(), null)) return null;
        if (GameLoginRouter.GAME.equals(targetPackage)) {
            try {
                ActivityInfo existing = component == null ? MetaPackageManager.resolveActivityInfo(web)
                        : MetaPackageManager.resolveActivityInfo(component);
                if (existing != null && GameLoginRouter.GAME.equals(existing.packageName) && existing.enabled) return null;
            } catch (RuntimeException ignored) {
                // A stale/unresolved game target must not fall through to real BGMI.
            }
        }

        // Prefer the game's actual embedded handler when it has one. Explicitly
        // replace an outside-browser component/package rather than handing the
        // unchanged Chrome/real-app target back to the SDK.
        Intent internal = new Intent(Intent.ACTION_VIEW, web.getData()).setPackage(GameLoginRouter.GAME)
                .addCategory(Intent.CATEGORY_BROWSABLE).addCategory(Intent.CATEGORY_DEFAULT);
        try {
            ActivityInfo info = MetaPackageManager.resolveActivityInfo(internal);
            if (info != null && GameLoginRouter.GAME.equals(info.packageName) && info.enabled) {
                Intent inGame = new Intent(web);
                inGame.setSelector(null);
                inGame.setPackage(GameLoginRouter.GAME);
                inGame.setComponent(new ComponentName(info.packageName, info.name));
                inGame.setFlags(inGame.getFlags() & ~Intent.FLAG_ACTIVITY_CLEAR_TASK);
                return inGame;
            }
        } catch (RuntimeException ignored) {
            // The scoped host WebView is still safer than falling out to the OS browser.
        }
        Log.i(TAG, "Keeping a game web request inside the loader");
        return new Intent().setComponent(new ComponentName(BuildConfig.APPLICATION_ID, GameWebLoginActivity.class.getName()))
                .setAction(GameWebPolicy.ACTION).setData(web.getData())
                .setFlags(original.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
