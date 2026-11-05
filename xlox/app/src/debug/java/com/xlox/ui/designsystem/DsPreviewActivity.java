package com.xlox.ui.designsystem;

import android.os.Bundle;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;

import com.xlox.R;
import com.xlox.ui.ThemeMode;

/**
 * Debug-only light/dark preview of the design system. This activity lives in
 * the debug sourceSet: it is absent from the release APK and is not a product
 * navigation destination.
 */
public class DsPreviewActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeMode.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ds_preview);
        ThemeMode.applyWindow(this);
        DesignSystem.applySafeDrawing(findViewById(R.id.previewRoot), true, false, false);
        DesignSystem.applyBottomInsets(findViewById(R.id.previewBottomNav));

        ImageButton toggle = findViewById(R.id.previewThemeToggle);
        ThemeMode.decorateToggle(this, toggle);
        toggle.setOnClickListener(v -> ThemeMode.toggle(this));

        PrimaryButton loadingButton = findViewById(R.id.previewLoadingButton);
        loadingButton.setLoading(true);

        StateView loading = findViewById(R.id.previewLoadingState);
        loading.showLoading(getString(R.string.ds_status_ok), 42);

        StateView error = findViewById(R.id.previewErrorState);
        error.showError(getString(R.string.ds_status_error));

        StateView empty = findViewById(R.id.previewEmptyState);
        empty.showEmpty(getString(R.string.ds_empty_headline),
                getString(R.string.ds_empty_body));
        empty.setAction(getString(R.string.ds_empty_action), v ->
                DesignSystem.snackbar(this, getString(R.string.ds_empty_action)));

        findViewById(R.id.previewPrimary).setOnClickListener(v ->
                DesignSystem.dialog(this,
                        getString(R.string.ds_dialog_headline),
                        getString(R.string.ds_dialog_body),
                        getString(R.string.ds_dialog_confirm), null, false,
                        getString(R.string.ds_dialog_cancel), null).show());
    }
}
