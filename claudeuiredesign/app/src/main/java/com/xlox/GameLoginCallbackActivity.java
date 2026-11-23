package com.xlox;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.xlox.utils.GameLoginRouter;

import net_62v.external.MetaActivityManager;
import net_62v.external.MetaPackageManager;

/**
 * Browser/provider -> virtual game handoff. The original SDK's intermediary
 * accepts a bare fbconnect URI, but other schemes require an internal wrapper
 * a real browser cannot supply. Use its public virtual activity API instead.
 * Never log the URI/extras: an OAuth callback can contain login credentials.
 */
public final class GameLoginCallbackActivity extends Activity {
    private static final String TAG = "GameLoginReturn";
    private static final String LEGACY_ACTIVITY = "top.bienvenido.mundo.manifest.MundoIntermediary";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent incoming) {
        try {
            String action = incoming == null ? null : incoming.getAction();
            String value = incoming == null ? null : incoming.getDataString();
            GameLoginRouter.Result result = GameLoginRouter.route(action, value, new GameLoginRouter.Container() {
                @Override
                public boolean isRunning() {
                    return MetaActivityManager.isAppRunning(GameLoginRouter.GAME, GameLoginRouter.USER);
                }

                @Override
                public GameLoginRouter.Target resolve(String uri) {
                    ActivityInfo info = MetaPackageManager.resolveActivityInfo(gameIntent(uri));
                    return info == null ? null : new GameLoginRouter.Target(info.packageName,
                            info.name, info.enabled, info.exported);
                }

                @Override
                public void launch(GameLoginRouter.Target target, String uri) {
                    Intent intent = gameIntent(uri).setComponent(new ComponentName(target.packageName, target.className));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    MetaActivityManager.launchIntent(intent, GameLoginRouter.USER);
                }
            });
            if (result == GameLoginRouter.Result.LEGACY) {
                // Facebook keeps exactly the original SDK handler. Construct a
                // fresh explicit intent: do not propagate untrusted extras,
                // ClipData, component names, or URI permission-grant flags.
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(value))
                        .setClassName(this, LEGACY_ACTIVITY));
            }
            Log.i(TAG, "Game login return: " + result.name());
        } catch (Throwable ignored) {
            // Exception messages can themselves embed the secret-bearing URI.
            Log.w(TAG, "Could not relay the game login return to the container");
        } finally {
            // Do not reopen home, kill/relaunch BGMI, or start the outside game:
            // any of those can discard the game's pending OAuth transaction.
            finish();
        }
    }

    private static Intent gameIntent(String uri) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setPackage(GameLoginRouter.GAME);
    }
}
