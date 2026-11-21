package com.xlox;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.xlox.ui.Ui;
import com.xlox.utils.GameLoginRouter;
import com.xlox.utils.GameWebPolicy;
import com.xlox.utils.GameWebNavigator;

import net_62v.external.MetaActivityManager;
import net_62v.external.MetaPackageManager;

/**
 * A game-initiated web page stays in this non-exported loader activity. Its
 * callback is resolved against the virtual game, never Android's outside-app
 * resolver. No JavaScript interface, token parsing, URL logging or SSL bypass.
 */
public final class GameWebLoginActivity extends AppCompatActivity {
    private static final String TAG = "GameWebLogin";
    private static boolean webViewPrepared;
    private WebView web;
    private TextView origin;
    private TextView error;
    private LinearProgressIndicator progress;
    private boolean returned;
    private int fallbackLoads;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        // The host profile must not share the virtual game's WebView data-dir
        // lock on Android 9+. Set this BEFORE inflating/constructing a WebView.
        prepareWebView();
        setContentView(R.layout.activity_game_web_login);
        Ui.edgeToEdge(this);
        View root = findViewById(R.id.gameWebRoot);
        Ui.onInsets(root, (bars, bottom, ime) -> root.setPadding(0, bars.top, 0, bottom));
        origin = findViewById(R.id.gameWebOrigin);
        error = findViewById(R.id.gameWebError);
        progress = findViewById(R.id.gameWebProgress);
        findViewById(R.id.gameWebClose).setOnClickListener(v -> closeToGame());
        web = findViewById(R.id.gameWebView);
        configureWebView();
        Intent intent = getIntent();
        String url = intent == null ? null : intent.getDataString();
        if (state != null || intent == null || !GameWebPolicy.ACTION.equals(intent.getAction())
                || !GameWebPolicy.isHttps(url)) {
            showError(R.string.game_web_interrupted);
            return;
        }
        origin.setText(GameWebPolicy.origin(url));
        web.loadUrl(url);
    }

    private static synchronized void prepareWebView() {
        if (webViewPrepared) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                WebView.setDataDirectorySuffix("loader_game_web");
            } catch (IllegalStateException ignored) {
                // An already-initialized host WebView has its profile/lock. Do
                // not clear cookies/data or attempt a second provider setup.
                Log.i(TAG, "Using the existing host WebView profile");
            }
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        webViewPrepared = true;
    }

    @SuppressLint("SetJavaScriptEnabled") // Required by the official provider page; no native JS bridge is exposed.
    private void configureWebView() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // target=_blank/window.open stays in this same WebView, not Chrome or
        // a second Android app. Keep the normal System WebView user agent.
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int value) {
                progress.setVisibility(value < 100 ? View.VISIBLE : View.INVISIBLE);
                progress.setProgressCompat(value, false);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return navigate(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) {
                    String scheme = request.getUrl().getScheme();
                    return !("https".equalsIgnoreCase(scheme) || "about".equals(scheme)
                            || "data".equals(scheme) || "blob".equals(scheme));
                }
                return navigate(request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap icon) {
                origin.setText(GameWebPolicy.origin(url));
                error.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError sslError) {
                handler.cancel();
                showError(R.string.net_ssl);
            }

            @Override
            public void onReceivedError(WebView view, int code, String description, String failingUrl) {
                showError(R.string.game_web_failed);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError failure) {
                if (request.isForMainFrame()) showError(R.string.game_web_failed);
            }
        });
    }

    /** true means consumed here. There is deliberately NO ACTION_VIEW/startActivity fallback. */
    private boolean navigate(String url) {
        if (returned) return true;
        GameWebNavigator.Decision decision = GameWebNavigator.navigate(url, new GameWebNavigator.Callbacks() {
            @Override
            public boolean forwardToGame(String value) {
                try {
                    Intent candidate = value.startsWith("intent:") ? Intent.parseUri(value, Intent.URI_INTENT_SCHEME)
                            : new Intent(Intent.ACTION_VIEW, Uri.parse(value));
                    return returnToVirtualGame(candidate);
                } catch (Exception ignored) {
                    // Never log URI/exception text: it can contain OAuth tokens.
                    Log.w(TAG, "Virtual callback resolution failed");
                    return false;
                }
            }

            @Override
            public String httpsFallback(String value) {
                try {
                    return Intent.parseUri(value, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url");
                } catch (Exception ignored) {
                    return null;
                }
            }
        });
        switch (decision.action) {
            case LOAD_WEB:
                return false;
            case RETURNED_TO_GAME:
                return true;
            case LOAD_FALLBACK:
                if (++fallbackLoads <= 3) {
                    web.loadUrl(decision.fallback);
                    return true;
                }
                break;
            default:
                break;
        }
        showError(R.string.game_web_return_failed);
        return true;
    }

    private boolean returnToVirtualGame(Intent candidate) {
        ComponentName component = candidate.getComponent();
        if ((component != null && !GameLoginRouter.GAME.equals(component.getPackageName()))
                || (candidate.getPackage() != null && !GameLoginRouter.GAME.equals(candidate.getPackage()))) return false;
        Uri data = candidate.getData();
        if (data == null || !GameWebPolicy.mayBeCallback(data.toString())) return false;
        if (!MetaActivityManager.isAppRunning(GameLoginRouter.GAME, GameLoginRouter.USER)) return false;

        Intent scoped = new Intent(Intent.ACTION_VIEW, data).setPackage(GameLoginRouter.GAME)
                .addCategory(Intent.CATEGORY_BROWSABLE).addCategory(Intent.CATEGORY_DEFAULT);
        ActivityInfo target = component == null ? MetaPackageManager.resolveActivityInfo(scoped)
                : MetaPackageManager.resolveActivityInfo(component);
        if (target == null || !GameLoginRouter.GAME.equals(target.packageName) || !target.enabled || !target.exported) return false;
        scoped.setComponent(new ComponentName(target.packageName, target.name));
        copyPrimitiveExtras(candidate, scoped);
        scoped.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        MetaActivityManager.launchIntent(scoped, GameLoginRouter.USER);
        returned = true;
        origin.setText(R.string.game_web_returning);
        progress.setVisibility(View.INVISIBLE);
        Log.i(TAG, "Web login return sent to the running virtual game");
        // Dispatch is not proof of provider authentication: do not fabricate
        // RESULT_OK or finish before the game's callback activity can run.
        // Close only after the virtual game actually takes this screen away.
        return true;
    }

    /** intent:// callbacks can carry provider data in primitive extras; never copy grant flags or parcelables. */
    private static void copyPrimitiveExtras(Intent source, Intent target) {
        Bundle extras = source.getExtras();
        if (extras == null) return;
        for (String key : extras.keySet()) {
            if (key.startsWith("android.") || key.startsWith("androidx.") || "browser_fallback_url".equals(key)) continue;
            Object value = extras.get(key);
            if (value instanceof String) target.putExtra(key, (String) value);
            else if (value instanceof Boolean) target.putExtra(key, ((Boolean) value).booleanValue());
            else if (value instanceof Integer) target.putExtra(key, ((Integer) value).intValue());
            else if (value instanceof Long) target.putExtra(key, ((Long) value).longValue());
        }
    }

    private void showError(int message) {
        error.setText(message);
        error.setVisibility(View.VISIBLE);
        progress.setVisibility(View.INVISIBLE);
    }

    private void closeToGame() {
        try {
            if (MetaActivityManager.isAppRunning(GameLoginRouter.GAME, GameLoginRouter.USER)) {
                MetaActivityManager.launchActivityExistingAppProcess(GameLoginRouter.GAME, GameLoginRouter.USER);
            }
        } catch (RuntimeException ignored) {
            Log.w(TAG, "Could not foreground the existing virtual game");
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else closeToGame();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (returned && !isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.setWebChromeClient(null);
            web.setWebViewClient(null);
            if (web.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) web.getParent()).removeView(web);
            }
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
