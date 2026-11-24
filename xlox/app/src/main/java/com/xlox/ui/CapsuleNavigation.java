package com.xlox.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import com.xlox.R;

/** Bounded segmented control. Its decoration is inset; its touch targets stay 48dp. */
public final class CapsuleNavigation extends FrameLayout {

    public interface DragListener {
        boolean onDragStart();
        void onDrag(float dx);
        void onDragEnd(boolean cancelled);
    }

    private final int touchSlop;
    private float progress;
    private View indicator;
    private int segmentWidth;
    private DragListener dragListener;
    private int pointerId = -1;
    private float downX;
    private float downY;
    private float lastX;
    private boolean dragging;
    private boolean vertical;

    public CapsuleNavigation(Context context) {
        this(context, null);
    }

    public CapsuleNavigation(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
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
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (indicator == null) return;
        segmentWidth = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight()) / 2;
        int inset = getResources().getDimensionPixelSize(R.dimen.nav_indicator_inset);
        // Only the visual selector shrinks by 2dp per edge, not the label targets.
        indicator.layout(getPaddingLeft() + inset, getPaddingTop() + inset,
                getPaddingLeft() + segmentWidth - inset, getHeight() - getPaddingBottom() - inset);
        moveIndicator();
    }

    public int getSegmentWidth() {
        return segmentWidth;
    }

    public void setProgress(float value) {
        progress = Math.max(0f, Math.min(1f, value));
        moveIndicator();
    }

    private void moveIndicator() {
        if (indicator != null) indicator.setTranslationX(progress * segmentWidth);
    }

    public void setDragListener(DragListener listener) {
        finishDrag(true);
        dragListener = listener;
    }

    private void beginStream(MotionEvent event) {
        pointerId = event.getPointerId(0);
        downX = lastX = event.getX();
        downY = event.getY();
        vertical = false;
    }

    private boolean move(MotionEvent event) {
        int index = event.findPointerIndex(pointerId);
        if (index < 0 || event.getPointerCount() != 1) {
            finishDrag(true);
            vertical = true;
            return false;
        }
        float x = event.getX(index);
        float dx = x - downX;
        float dy = event.getY(index) - downY;
        if (!dragging && !vertical && Math.max(Math.abs(dx), Math.abs(dy)) > touchSlop) {
            vertical = Math.abs(dy) >= Math.abs(dx);
            if (!vertical && dragListener != null) {
                dragging = dragListener.onDragStart();
                if (dragging && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            }
        }
        if (!dragging) return false;
        dragListener.onDrag(x - lastX);
        lastX = x;
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginStream(event);
                return false;
            case MotionEvent.ACTION_MOVE:
                // Returning true lets Android CANCEL the label's click. Merely
                // listening on a label would leave clicks alive after a drag.
                return move(event) || vertical;
            case MotionEvent.ACTION_POINTER_DOWN:
                finishDrag(true);
                vertical = true;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                finishDrag(event.getActionMasked() == MotionEvent.ACTION_CANCEL);
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginStream(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                move(event);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                finishDrag(true);
                vertical = true;
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) finishDrag(false);
                else if (!vertical) performClick();
                pointerId = -1;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void finishDrag(boolean cancelled) {
        boolean wasDragging = dragging;
        dragging = false;
        pointerId = -1;
        if (wasDragging) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            if (dragListener != null) dragListener.onDragEnd(cancelled);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        finishDrag(true);
        super.onDetachedFromWindow();
    }
}
