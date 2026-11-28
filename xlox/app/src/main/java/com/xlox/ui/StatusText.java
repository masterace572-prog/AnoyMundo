package com.xlox.ui;

import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.xlox.R;

/** Idempotent status rendering: unchanged refreshes do not rebuild dots or relayout text. */
public final class StatusText {

    private StatusText() { }

    public static void set(TextView view, CharSequence text, int dotColor, int textColor) {
        if (view.getVisibility() != View.VISIBLE) view.setVisibility(View.VISIBLE);
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
        int color = ContextCompat.getColor(view.getContext(), textColor);
        if (view.getCurrentTextColor() != color) view.setTextColor(color);
        Object previousDot = view.getTag(R.id.tag_status_dot);
        if (previousDot instanceof Integer && (Integer) previousDot == dotColor) return;
        Drawable dot = null;
        if (dotColor != 0) {
            dot = ContextCompat.getDrawable(view.getContext(), R.drawable.dot);
            if (dot != null) {
                dot = DrawableCompat.wrap(dot.mutate());
                DrawableCompat.setTint(dot, ContextCompat.getColor(view.getContext(), dotColor));
                int size = view.getResources().getDimensionPixelSize(R.dimen.dot);
                dot.setBounds(0, 0, size, size);
            }
        }
        view.setCompoundDrawablesRelative(dot, null, null, null);
        view.setTag(R.id.tag_status_dot, dotColor);
    }
}
