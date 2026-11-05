package com.xlox.ui.designsystem;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatButton;

import com.xlox.R;

/**
 * Compact loading / status / empty / error row. It lives in a fixed-height
 * container, so switching states never changes the surrounding layout.
 */
public class StateView extends LinearLayout {

    private final View rule;
    private final ProgressBar indicator;
    private final ProgressBar progress;
    private final TextView percent;
    private final TextView detail;
    private final AppCompatButton action;

    public StateView(Context context) {
        this(context, null);
    }

    public StateView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.ds_state, this, true);
        rule = findViewById(R.id.stateRule);
        indicator = findViewById(R.id.stateIndicator);
        progress = findViewById(R.id.stateProgress);
        percent = findViewById(R.id.statePercent);
        detail = findViewById(R.id.stateDetail);
        action = findViewById(R.id.stateAction);
        setVisibility(GONE);
    }

    /** percent &lt; 0 shows the indeterminate circular indicator. */
    public void showLoading(String label, int value) {
        setVisibility(VISIBLE);
        rule.setBackgroundColor(Tokens.accent(getContext()));
        action.setVisibility(GONE);
        detail.setVisibility(VISIBLE);
        updateProgress(label, value);
        DsMotion.fadeIn(this);
    }

    public void updateProgress(String label, int value) {
        detail.setText(label);
        boolean determinate = value >= 0;
        indicator.setVisibility(determinate ? GONE : VISIBLE);
        progress.setVisibility(determinate ? VISIBLE : GONE);
        percent.setVisibility(determinate ? VISIBLE : GONE);
        if (determinate) {
            progress.setIndeterminate(false);
            progress.setProgress(Math.max(0, Math.min(100, value)));
            percent.setText(getContext().getString(R.string.percent, value));
        }
    }

    /** Plain status text, no loading indicator. */
    public void showStatus(String text) {
        showMessage(text, Tokens.hairlineStrong(getContext()), false);
    }

    public void showError(String sentence) {
        showMessage(sentence, Tokens.error(getContext()), true);
    }

    public void showEmpty(String title, String sentence) {
        showMessage(title + " · " + sentence, Tokens.hairlineStrong(getContext()), true);
    }

    private void showMessage(String text, int ruleColour, boolean withAction) {
        setVisibility(VISIBLE);
        rule.setBackgroundColor(ruleColour);
        indicator.setVisibility(GONE);
        progress.setVisibility(GONE);
        percent.setVisibility(GONE);
        detail.setVisibility(VISIBLE);
        detail.setText(text);
        action.setVisibility(withAction ? VISIBLE : GONE);
        if (withAction) {
            action.setText(getContext().getString(R.string.dismiss));
            action.setOnClickListener(v -> hide());
        }
        DsMotion.fadeIn(this);
    }

    public void setAction(CharSequence label, OnClickListener listener) {
        action.setText(label);
        action.setVisibility(VISIBLE);
        action.setOnClickListener(listener);
    }

    public void setDetail(String text) {
        detail.setText(text);
    }

    public void hide() {
        DsMotion.fadeOut(this, () -> setVisibility(GONE));
    }
}
