package com.xlox.utils;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.xlox.R;
import com.xlox.ui.designsystem.DesignSystem;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.regex.Pattern;

import net_62v.external.MetaPackageManager;
import net_62v.external.MetaStorageManager;

public class AppManager extends AppCompatActivity {

    private final Context ctx;

    public static final int INSTALL_APP = 1;
    public static final int COPY_OBB = 3;

    public interface CopyCallback {
        void onCopyCompleted(boolean success);
        default void onProgressUpdate(int progress) {}
    }

    public AppManager(Context ctx) {
        this.ctx = ctx;
    }

    @SuppressLint("StaticFieldLeak")
    public void appManager(String pkg, int method, CopyCallback callback, boolean showDialog) {

        new AsyncTask<Void, Integer, Boolean>() {

            String errorMessage = "";

            Dialog progressDialog;
            ProgressBar progressBar;
            TextView progressText;

            long totalBytes = 0;
            long copiedBytes = 0;

            @Override
            protected void onPreExecute() {

                if (method == COPY_OBB && showDialog) {

                    progressDialog = new Dialog(ctx, R.style.ThemeOverlay.Ds.Dialog);
                    progressDialog.getWindow().setBackgroundDrawable(
                            new android.graphics.drawable.ColorDrawable(
                                    android.graphics.Color.TRANSPARENT));
                    progressDialog.setContentView(R.layout.progress_dialog);
                    progressDialog.setCancelable(false);

                    progressBar = progressDialog.findViewById(R.id.progressBar);
                    progressText = progressDialog.findViewById(R.id.progressText);

                    if (progressBar != null) {
                        progressBar.setMax(100);
                        progressBar.setProgress(0);
                    }

                    if (progressText != null) {
                        progressText.setText(ctx.getString(R.string.percent, 0));
                    }

                    progressDialog.show();
                }
            }

            @Override
            protected Boolean doInBackground(Void... voids) {

                if (method == COPY_OBB) {
                    return copyObbFolder();
                }

                if (method == INSTALL_APP) {
                    return installApp(pkg);
                }

                return false;
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                if (callback != null) {
                    callback.onProgressUpdate(values[0]);
                }

                if (progressBar != null) {
                    progressBar.setProgress(values[0]);
                }

                if (progressText != null) {
                    progressText.setText(ctx.getString(R.string.percent, values[0]));
                }
            }

            @Override
            protected void onPostExecute(Boolean success) {

                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }

                if (callback != null) {
                    callback.onCopyCompleted(success);
                } else {
                    showResultDialog(success, errorMessage);
                }
            }

            private boolean installApp(String pkg) {

                try {

                    PackageInfo packageInfo = ctx.getPackageManager().getPackageInfo(pkg, 0);

                    String apkPath = packageInfo.applicationInfo.sourceDir;

                    net_62v.external.MetaApplicationInstaller.installAppByPath(apkPath);

                    boolean result = MetaPackageManager.isInnerAppInstalled(pkg);

                    if (!result) {
                        errorMessage = ctx.getString(R.string.sandbox_install_failed);
                        return false;
                    }

                    return true;

                } catch (PackageManager.NameNotFoundException e) {
                    errorMessage = ctx.getString(R.string.package_not_found);
                    return false;

                } catch (Exception e) {
                    errorMessage = e.getMessage();
                    return false;
                }
            }

            private boolean copyObbFolder() {

                try {

                    File sourceDir = new File("/storage/emulated/0/Android/obb/" + pkg);

                    if (!sourceDir.exists()) {
                        errorMessage = ctx.getString(R.string.obb_folder_not_found);
                        return false;
                    }

                    File destDir = getMundoObbDir(pkg);

                    if (sourceDir.getAbsolutePath().equals(destDir.getAbsolutePath())) {
                        return true;
                    }

                    if (!destDir.exists()) {
                        destDir.mkdirs();
                    }

                    File mainObb = findMainObb(sourceDir, pkg);
                    if (mainObb == null) {
                        errorMessage = ctx.getString(R.string.main_obb_not_found);
                        return false;
                    }

                    totalBytes = mainObb.length();
                    copiedBytes = 0;

                    return copyFile(mainObb, new File(destDir, mainObb.getName()));

                } catch (Exception e) {
                    errorMessage = e.getMessage();
                    return false;
                }
            }

            private File findMainObb(File sourceDir, String packageName) {
                File[] files = sourceDir.listFiles();
                if (files == null) return null;

                String pattern = "^main\\.[0-9]+\\." + Pattern.quote(packageName) + "\\.obb$";
                File selected = null;
                long selectedVersion = -1L;
                for (File file : files) {
                    if (!file.isFile() || !file.getName().matches(pattern)) continue;
                    String[] parts = file.getName().split("\\.");
                    long version = -1L;
                    if (parts.length > 1) {
                        try {
                            version = Long.parseLong(parts[1]);
                        } catch (NumberFormatException ignored) {}
                    }
                    if (selected == null || version > selectedVersion) {
                        selected = file;
                        selectedVersion = version;
                    }
                }
                return selected;
            }

            private boolean copyFile(File source, File target) {
                try {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();

                    FileInputStream input = new FileInputStream(source);
                    FileOutputStream output = new FileOutputStream(target);
                    byte[] buffer = new byte[1024 * 1024];
                    int length;

                    while ((length = input.read(buffer)) > 0) {
                        output.write(buffer, 0, length);
                        copiedBytes += length;
                        int progress = totalBytes > 0 ? (int) ((copiedBytes * 100) / totalBytes) : 100;
                        publishProgress(progress);
                    }

                    input.close();
                    output.flush();
                    output.close();
                    return true;
                } catch (Exception e) {
                    errorMessage = e.getMessage();
                    return false;
                }
            }

            private long getFolderSize(File dir) {

                long size = 0;
                File[] files = dir.listFiles();

                if (files != null) {
                    for (File file : files) {
                        if (file.isDirectory()) {
                            size += getFolderSize(file);
                        } else {
                            size += file.length();
                        }
                    }
                }

                return size;
            }

            private void showResultDialog(boolean success, String errorMessage) {

                CharSequence title = ctx.getString(
                        success ? R.string.dialog_success : R.string.dialog_error);
                CharSequence message = success
                        ? ctx.getString(method == INSTALL_APP
                                ? R.string.app_installed : R.string.obb_ready)
                        : errorMessage;
                DesignSystem.dialog(ctx, title, message,
                        ctx.getString(R.string.close), null, !success,
                        null, null).show();
            }

        }.execute();
    }

    private File getMundoObbDir(String pkg) {
        try {
            return new File(
                    MetaStorageManager.obtainAppExternalStorageDir(pkg) + "/Android/obb",
                    pkg
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // FIXED: Now accepts a String parameter for package name
    public boolean checkObbContainer(String packageName) {
        File destDir = getMundoObbDir(packageName);
        if (!destDir.exists() || !destDir.isDirectory()) return false;
        File[] files = destDir.listFiles();
        if (files == null) return false;
        String pattern = "^main\\.[0-9]+\\." + Pattern.quote(packageName) + "\\.obb$";
        for (File file : files) {
            if (file.isFile() && file.getName().matches(pattern)) return true;
        }
        return false;
    }

    public void copyObbFolderAsync(String pkg, final CopyCallback callback) {
        copyObbFolderAsync(pkg, callback, true);
    }

    public void copyObbFolderAsync(String pkg, final CopyCallback callback, boolean showDialog) {
        if (checkObbContainer(pkg)) {
            if (callback != null) {
                callback.onCopyCompleted(true);
            }
            return;
        }

        appManager(pkg, COPY_OBB, callback, showDialog);
    }
}
