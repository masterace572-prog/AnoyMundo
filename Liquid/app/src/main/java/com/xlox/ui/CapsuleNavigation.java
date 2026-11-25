package com.xlox.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import com.xlox.R;

/**
 * Liquid-only navigation: clear glass shell, a softly frosted refractive lens,
 * and bounded, touch-driven shape springs. Page/touch positions remain exact.
 */
public final class CapsuleNavigation extends FrameLayout {
    public interface DragListener {
        boolean onDragStart();
        void onDrag(float dx);
        void onDragEnd(boolean cancelled);
    }

    private final int touchSlop;
    private final LiquidGlassMotion liquid = new LiquidGlassMotion();
    private final LiquidGlassRenderer glass;
    private final LiquidGlassBackdrop backdrop;
    private final RectF lensAnchor = new RectF();
    private float progress;
    private View indicator;
    private int segmentWidth;
    private DragListener dragListener;
    private int pointerId = -1;
    private float downX;
    private float downY;
    private float lastX;
    private float focusX;
    private float focusY;
    private boolean dragging;
    private boolean axisChosen;
    private boolean vertical;
    private boolean framePosted;
    private final Runnable glassFrame = new Runnable() {
        @Override public void run() {
            framePosted = false;
            if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE || !isShown()) {
                liquid.snap(progress);
                return;
            }
            boolean moving = liquid.advance(SystemClock.uptimeMillis(), Ui.reducedMotion(getContext()));
            invalidate();
            if (moving) scheduleGlassFrame();
        }
    };

    public CapsuleNavigation(Context context) { this(context, null); }

    public CapsuleNavigation(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        glass = new LiquidGlassRenderer(context);
        backdrop = new LiquidGlassBackdrop(this);
        setWillNotDraw(false);
    }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        indicator = findViewById(R.id.navigationIndicator);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = getResources().getDimensionPixelSize(R.dimen.nav_capsule_width);
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            width = Math.min(width, MeasureSpec.getSize(widthMeasureSpec));
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (indicator == null) return;
        segmentWidth = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight()) / 2;
        int inset = getResources().getDimensionPixelSize(R.dimen.nav_indicator_inset);
        // The visible lens is inset. Both label hit targets remain at least 48dp.
        indicator.layout(getPaddingLeft() + inset, getPaddingTop() + inset,
                getPaddingLeft() + segmentWidth - inset, getHeight() - getPaddingBottom() - inset);
        moveIndicator();
    }

    public int getSegmentWidth() { return segmentWidth; }

    public void setProgress(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return;
        progress = Math.max(0f, Math.min(1f, value));
        liquid.progress(progress, SystemClock.uptimeMillis());
        moveIndicator();
        if (Ui.reducedMotion(getContext())) liquid.snap(progress);
        else scheduleGlassFrame();
        invalidate();
    }

    private void moveIndicator() {
        if (indicator != null) indicator.setTranslationX(progress * segmentWidth);
    }

    public void setBackdropSource(View source) {
        backdrop.source(source);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (indicator != null) {
            lensAnchor.set(indicator.getLeft() + indicator.getTranslationX(), indicator.getTop(),
                    indicator.getRight() + indicator.getTranslationX(), indicator.getBottom());
        } else lensAnchor.setEmpty();
        // Child labels are drawn by FrameLayout AFTER these optics, always sharp.
        glass.draw(canvas, getWidth(), getHeight(), lensAnchor, liquid.flow(), liquid.pressure(),
                focusX, focusY, backdrop);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            focusX = LiquidGlassMotion.clamp(2f * event.getX() / Math.max(1, getWidth()) - 1f, -1f, 1f);
            focusY = LiquidGlassMotion.clamp(2f * event.getY() / Math.max(1, getHeight()) - 1f, -1f, 1f);
        }
        if (action == MotionEvent.ACTION_DOWN) liquid.press(true);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN) liquid.press(false);
        if (Ui.reducedMotion(getContext())) liquid.snap(progress);
        else scheduleGlassFrame();
        invalidate();
        return super.dispatchTouchEvent(event);
    }

    private void scheduleGlassFrame() {
        if (framePosted || !isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
        framePosted = true;
        postOnAnimation(glassFrame);
    }

    private void stopGlassMotion() {
        removeCallbacks(glassFrame);
        framePosted = false;
        liquid.snap(progress);
    }

    public void disposeGlass() {
        stopGlassMotion();
        backdrop.source(null);
    }

    // Package-private observability for host tests, never a screen-export API.
    LiquidGlassBackdrop backdrop() { return backdrop; }
    boolean hasScheduledGlassFrame() { return framePosted; }
    float glassFlow() { return liquid.flow(); }

    public void setDragListener(DragListener listener) {
        finishDrag(true);
        dragListener = listener;
    }

    private void beginStream(MotionEvent event) {
        pointerId = event.getPointerId(0);
        downX = lastX = event.getX();
        downY = event.getY();
        axisChosen = false;
        vertical = false;
    }

    private boolean move(MotionEvent event) {
        int index = event.findPointerIndex(pointerId);
        if (index < 0 || event.getPointerCount() != 1) {
            finishDrag(true);
            axisChosen = vertical = true;
            return false;
        }
        float x = event.getX(index);
        float dx = x - downX;
        float dy = event.getY(index) - downY;
        if (!axisChosen && Math.max(Math.abs(dx), Math.abs(dy)) > touchSlop) {
            axisChosen = true;
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

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginStream(event);
                return false;
            case MotionEvent.ACTION_MOVE:
                // Android cancels the label click as soon as a drag owns the stream.
                return move(event) || axisChosen;
            case MotionEvent.ACTION_POINTER_DOWN:
                finishDrag(true);
                axisChosen = vertical = true;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                finishDrag(event.getActionMasked() == MotionEvent.ACTION_CANCEL);
                return false;
            default:
                return false;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
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
                axisChosen = vertical = true;
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) finishDrag(false);
                else if (!axisChosen) performClick();
                pointerId = -1;
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() { return super.performClick(); }

    private void finishDrag(boolean cancelled) {
        boolean wasDragging = dragging;
        dragging = false;
        pointerId = -1;
        if (wasDragging) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            if (dragListener != null) dragListener.onDragEnd(cancelled);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        liquid.snap(progress);
        backdrop.attach();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // Android can call this during View construction, before final helpers exist.
        if (backdrop == null) return;
        if (visibility == VISIBLE) backdrop.attach();
        else {
            stopGlassMotion();
            backdrop.detach();
        }
    }

    @Override protected void onDetachedFromWindow() {
        finishDrag(true);
        stopGlassMotion();
        backdrop.detach();
        super.onDetachedFromWindow();
    }
}
