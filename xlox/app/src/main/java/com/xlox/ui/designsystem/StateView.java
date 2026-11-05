package com.xlox.ui.designsystem;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;

import com.xlox.R;

/**
 * The single implementation of loading / empty / error states (B.6).
 * Loading uses a 20dp monochrome circular indicator or a 4dp accent linear
 * progress on surfaceSubtle. Error uses plain semantic text with a 2dp left
 * rule, never a coloured pill. Empty uses a serif headline, one
 * textSecondary sentence and one Secondary button.
 */
public class StateView extends LinearLayout {

    private final View ruleRow;
    private final View rule;
    private final TextView headline;
    private final TextView supporting;
    private final View loadingRow;
    private final ProgressBar indicator;
    private final ProgressBar progress;
    private final TextView percentText;
    private final TextView detail;
    private final AppCompatButton action;

    public StateView(Context context) {
        this(context, null);
    }

    public StateView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.ds_state, this, true);
        ruleRow = findViewById(R.id.stateRuleRow);
        rule = findViewById(R.id.stateRule);
        headline = findViewById(R.id.stateHeadline);
        supporting = findViewById(R.id.stateSupporting);
        loadingRow = findViewById(R.id.stateLoadingRow);
        indicator = findViewById(R.id.stateIndicator);
        progress = findViewById(R.id.stateProgress);
        percentText = findViewById(R.id.statePercent);
        detail = findViewById(R.id.stateDetail);
        action = findViewById(R.id.stateAction);
    }

    /** percent &lt; 0 shows the indeterminate circular indicator. */
    public void showLoading(String label, int percent) {
        setVisibility(VISIBLE);
        ruleRow.setVisibility(GONE);
        action.setVisibility(GONE);
        loadingRow.setVisibility(VISIBLE);
        updateProgress(label, percent);
        DsMotion.fadeIn(this);
    }

    /** Update an already-visible loading state without re-running the fade. */
    public void updateProgress(String label, int percent) {
        detail.setText(label);
        boolean determinate = percent >= 0;
        indicator.setVisibility(determinate ? GONE : VISIBLE);
        progress.setVisibility(determinate ? VISIBLE : GONE);
        percentText.setVisibility(determinate ? VISIBLE : GONE);
        if (determinate) {
            progress.setIndeterminate(false);
            progress.setProgress(Math.max(0, Math.min(100, percent)));
            percentText.setText(getContext().getString(R.string.percent, percent));
        }
    }

    /** Plain status text, no loading indicator and no coloured background. */
    public void showStatus(String text) {
        setVisibility(VISIBLE);
        ruleRow.setVisibility(GONE);
        loadingRow.setVisibility(GONE);
        action.setVisibility(GONE);
        detail.setText(text);
        DsMotion.fadeIn(this);
    }

    public void showError(String sentence) {
        showMessage(getContext().getString(R.string.state_error_headline), sentence,
                ContextCompat.getColor(getContext(), R.color.error));
    }

    public void showEmpty(String title, String sentence) {
        showMessage(title, sentence,
                ContextCompat.getColor(getContext(), R.color.outline));
    }

    private void showMessage(String title, String sentence, int ruleColour) {
        setVisibility(VISIBLE);
        loadingRow.setVisibility(GONE);
        ruleRow.setVisibility(VISIBLE);
        rule.setBackgroundColor(ruleColour);
        headline.setText(title);
        supporting.setText(sentence);
        detail.setText("");
        action.setVisibility(VISIBLE);
        action.setText(getContext().getString(R.string.dismiss));
        action.setOnClickListener(v -> hide());
        DsMotion.fadeIn(this);
    }

    public void setAction(CharSequence label, OnClickListener listener) {
        action.setText(label);
        action.setVisibility(VISIBLE);
        action.setOnClickListener(listener);
    }

    public void hide() {
        DsMotion.fadeOut(this, () -> setVisibility(GONE));
    }

    public void setDetail(String text) {
        detail.setText(text);
    }
}
