package com.xlox;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;

import com.xlox.ui.ThemeMode;
import com.xlox.ui.designsystem.DesignSystem;

public class FacebookActivity extends AppCompatActivity {

    private WebView webView;
    private ProgressBar progressBar;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeMode.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_facebook);
        ThemeMode.applyWindow(this);
        DesignSystem.applySafeDrawing(findViewById(R.id.facebookRoot), true, true, false);

        webView = findViewById(R.id.facebookWebView);
        progressBar = findViewById(R.id.facebookProgressBar);

        ImageButton back = findViewById(R.id.backButton);
        if (back != null) back.setOnClickListener(v -> onBackPressed());

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Enable cookies for persistent login
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }
        });

        webView.loadUrl(getString(R.string.facebook_login_url));
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
