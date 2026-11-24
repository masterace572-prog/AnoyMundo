package com.xlox.ui;

import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.appcompat.widget.TooltipCompat;
import androidx.core.view.ViewCompat;

import com.xlox.R;

/** Show/hide changes only presentation: no keyboard/input-type or typeface reset. */
public final class LicenceKeyVisibility {

    private final EditText input;
    private final ImageButton toggle;
    private boolean visible;

    public LicenceKeyVisibility(EditText input, ImageButton toggle) {
        this.input = input;
        this.toggle = toggle;
        setVisible(false);
        toggle.setOnClickListener(v -> {
            Ui.tap(v);
            setVisible(!visible);
        });
    }

    public void hide() {
        if (visible) setVisible(false);
    }

    private void setVisible(boolean shown) {
        visible = shown;
        int start = input.getSelectionStart();
        int end = input.getSelectionEnd();
        input.setTransformationMethod(shown ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
        if (start >= 0 && end >= 0) {
            int length = input.length();
            input.setSelection(Math.min(start, length), Math.min(end, length));
        }
        // The glyph and spoken label describe the NEXT action, not the old state.
        toggle.setImageResource(shown ? R.drawable.ic_eye_off : R.drawable.ic_eye);
        toggle.setSelected(shown);
        String action = toggle.getContext().getString(shown
                ? R.string.hide_key_description : R.string.show_key_description);
        toggle.setContentDescription(action);
        TooltipCompat.setTooltipText(toggle, action);
        ViewCompat.setStateDescription(toggle, toggle.getContext().getString(shown
                ? R.string.key_state_visible : R.string.key_state_hidden));
    }
}
