package com.xlox.ui.designsystem;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.xlox.R;
import com.xlox.ui.ThemeMode;

/**
 * Debug-only light/dark preview of the v2 design system. Lives in the debug
 * sourceSet: absent from release APKs and not a product navigation route.
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

        ThemeToggleView toggle = findViewById(R.id.previewThemeToggle);
        ThemeMode.decorateToggle(this, toggle);
        toggle.setOnClickListener(v -> ThemeMode.toggle(this));

        ((PrimaryButton) findViewById(R.id.previewLoadingButton)).setBusy(true);

        StateView loading = findViewById(R.id.previewLoadingState);
        loading.showLoading(getString(R.string.ds_loading), 42);
        ((StateView) findViewById(R.id.previewErrorState))
                .showError(getString(R.string.ds_error));
        StateView empty = findViewById(R.id.previewEmptyState);
        empty.showEmpty(getString(R.string.ds_empty), getString(R.string.ds_card_body));
        empty.setAction(getString(R.string.dismiss), v -> empty.hide());

        findViewById(R.id.previewPrimary).setOnClickListener(v ->
                DesignSystem.dialog(this,
                        getString(R.string.ds_card_headline),
                        getString(R.string.ds_card_body),
                        getString(R.string.close), null, false,
                        getString(R.string.ds_text), null).show());
    }
}
