package com.xlox.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.xlox.R;

/** Two end-aligned header statuses: one baseline when they fit, two rows otherwise. */
public final class StatusPairLayout extends ViewGroup {

    private final int horizontalGap;
    private final int verticalGap;
    private boolean stacked;
    private int baseline;

    public StatusPairLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        horizontalGap = getResources().getDimensionPixelSize(R.dimen.space_12);
        verticalGap = getResources().getDimensionPixelSize(R.dimen.space_2);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE : Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        int rowWidth = 0;
        int maxWidth = 0;
        int height = 0;
        int descent = 0;
        int count = 0;
        int state = 0;
        baseline = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChild(child, widthSpec, heightSpec);
            rowWidth += (count++ == 0 ? 0 : horizontalGap) + child.getMeasuredWidth();
            maxWidth = Math.max(maxWidth, child.getMeasuredWidth());
            height += child.getMeasuredHeight();
            int childBaseline = child.getBaseline();
            baseline = Math.max(baseline, Math.max(0, childBaseline));
            descent = Math.max(descent, child.getMeasuredHeight() - Math.max(0, childBaseline));
            state = combineMeasuredStates(state, child.getMeasuredState());
        }
        stacked = rowWidth > width;
        int desiredWidth = stacked ? maxWidth : rowWidth;
        int desiredHeight = stacked ? height + Math.max(0, count - 1) * verticalGap : baseline + descent;
        setMeasuredDimension(resolveSizeAndState(desiredWidth + getPaddingLeft() + getPaddingRight(), widthSpec, state),
                resolveSizeAndState(desiredHeight + getPaddingTop() + getPaddingBottom(), heightSpec,
                        state << MEASURED_HEIGHT_STATE_SHIFT));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int end = getWidth() - getPaddingRight();
        int y = getPaddingTop();
        // In a horizontal row lay out backwards from the shared end edge.
        for (int n = 0; n < getChildCount(); n++) {
            int i = stacked ? n : getChildCount() - n - 1;
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int h = child.getMeasuredHeight();
            int w = child.getMeasuredWidth();
            int childTop = stacked ? y : y + baseline - Math.max(0, child.getBaseline());
            child.layout(end - w, childTop, end, childTop + h);
            if (stacked) y += h + verticalGap;
            else end -= w + horizontalGap;
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
}
