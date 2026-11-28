package com.ccore.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.ccore.CCoreCore;
import com.ccore.R;
import com.ccore.utils.Slog;

/**
 * Created by BlackBox on 2022/2/24.
 */
public class LauncherActivity extends Activity {
    public static final String TAG = "SplashScreen";

    public static final String KEY_INTENT = "launch_intent";
    public static final String KEY_PKG = "launch_pkg";
    public static final String KEY_USER_ID = "launch_user_id";
    private boolean isRunning = false;

    public static void launch(Intent intent, int userId) {
        Intent splash = new Intent();
        splash.setClass(CCoreCore.getContext(), LauncherActivity.class);
        splash.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        splash.putExtra(LauncherActivity.KEY_INTENT, intent);
        String pkg = intent.getPackage();
        if (pkg == null && intent.getComponent() != null) {
            pkg = intent.getComponent().getPackageName();
        }
        splash.putExtra(LauncherActivity.KEY_PKG, pkg);
        splash.putExtra(LauncherActivity.KEY_USER_ID, userId);
        CCoreCore.getContext().startActivity(splash);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }
        Intent launchIntent = intent.getParcelableExtra(KEY_INTENT);
        String packageName = intent.getStringExtra(KEY_PKG);
        int userId = intent.getIntExtra(KEY_USER_ID, 0);

        PackageInfo packageInfo = CCoreCore.getBPackageManager().getPackageInfo(packageName, 0, userId);
        if (packageInfo == null) {
            Slog.e(TAG, packageName + " not installed!");
            finish();
            return;
        }
        Drawable drawable = packageInfo.applicationInfo.loadIcon(CCoreCore.getPackageManager());
        setContentView(R.layout.activity_launcher);
        findViewById(R.id.iv_icon).setBackgroundDrawable(drawable);
        new Thread(() -> CCoreCore.getBActivityManager().startActivity(launchIntent, userId)).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isRunning = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isRunning) {
            finish();
        }
    }
}
