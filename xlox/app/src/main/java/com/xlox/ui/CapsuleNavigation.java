package com.xlox.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import com.xlox.R;

/** A bounded bottom capsule whose highlight follows the pager, not a second animation. */
public final class CapsuleNavigation extends FrameLayout {

    private float progress;
    private View indicator;

    public CapsuleNavigation(Context context) {
        this(context, null);
    }

    public CapsuleNavigation(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        indicator = findViewById(R.id.navigationIndicator);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = getResources().getDimensionPixelSize(R.dimen.nav_capsule_width);
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            width = Math.min(width, MeasureSpec.getSize(widthMeasureSpec));
        }
        // wrap_content has a 280dp preferred width but can shrink on narrow / split
        // screens. Height remains content-driven for large accessibility fonts.
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (indicator == null) return;
        int half = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight()) / 2;
        indicator.layout(getPaddingLeft(), getPaddingTop(), getPaddingLeft() + half,
                getHeight() - getPaddingBottom());
        moveIndicator();
    }

    public void setProgress(float value) {
        progress = Math.max(0f, Math.min(1f, value));
        moveIndicator();
    }

    private void moveIndicator() {
        if (indicator != null) indicator.setTranslationX(progress * indicator.getWidth());
    }
}
