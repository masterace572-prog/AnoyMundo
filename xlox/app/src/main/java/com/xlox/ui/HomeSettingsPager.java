package com.xlox.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.xlox.R;

/** Two retained, opaque pages using ViewPager's real horizontal drag and settle. */
public final class HomeSettingsPager extends ViewPager {

    public static final int HOME = 0;
    public static final int SETTINGS = 1;

    private static final int NO_POINTER = -1;
    private static final int UNDECIDED = 0;
    private static final int HORIZONTAL = 1;
    private static final int VERTICAL = 2;
    private final int directionSlop;
    private int direction = UNDECIDED;
    private int pointerId = NO_POINTER;
    private float downX;
    private float downY;

    /** Programmatic construction (also exercised by the host Android tests). */
    public HomeSettingsPager(Context context) {
        this(context, null);
        LayoutInflater inflater = LayoutInflater.from(context);
        inflater.inflate(R.layout.page_home, this, true);
        inflater.inflate(R.layout.page_settings, this, true);
        attachPages();
    }

    public HomeSettingsPager(Context context, AttributeSet attrs) {
        super(context, attrs);
        directionSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        // These English navigation labels always keep Home left / Settings right.
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        setOffscreenPageLimit(1);
    }

    /**
     * Decide once per real finger stream, before a child can claim interception.
     * Stock ViewPager requires horizontal movement to exceed twice the vertical
     * movement. A diagonal stroke otherwise latches "unable to drag", and a
     * ScrollView can then disallow all later interception. These two pages have
     * no horizontal child scrollers: the predominant axis should own the stroke.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                direction = UNDECIDED;
                pointerId = event.getPointerId(0);
                downX = event.getX();
                downY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointerId);
                if (direction == UNDECIDED && index >= 0) {
                    float dx = Math.abs(event.getX(index) - downX);
                    float dy = Math.abs(event.getY(index) - downY);
                    if (Math.max(dx, dy) > directionSlop) {
                        direction = dx > dy ? HORIZONTAL : VERTICAL;
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // Do not turn an undecided two-finger/accessibility gesture into
                // a page swipe. ViewPager still handles pointer hand-off once
                // an actual horizontal drag is already in progress.
                if (direction == UNDECIDED) direction = VERTICAL;
                break;
            default:
                break;
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            direction = UNDECIDED;
            pointerId = NO_POINTER;
        }
        return handled;
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // A child fling/pressed button must not lock the pager before the user
        // has picked an axis. Once vertical, leave the whole stream to the child.
        if (!disallowIntercept || direction == VERTICAL) {
            super.requestDisallowInterceptTouchEvent(disallowIntercept);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            boolean catchingSettle = super.onInterceptTouchEvent(event);
            if (catchingSettle) direction = HORIZONTAL;
            return catchingSettle;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (direction != HORIZONTAL) return false;
            // Feed the original event to ViewPager's own drag/velocity engine,
            // without its overly strict diagonal interception gate. Android
            // dispatch cancels the child's pressed/scroll state when we take over.
            super.onTouchEvent(event);
            return true;
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Empty page space can be the touch target too; it obeys the same axis
        // lock as ScrollView/button descendants, with no synthetic/fake drags.
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && direction != HORIZONTAL) {
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        // activity_main declares both <include>s. IDs are visible to Android Lint
        // and MAct during onCreate, before ViewPager's first measure/populate.
        attachPages();
    }

    private void attachPages() {
        final View[] pages = {findViewById(R.id.homePage), findViewById(R.id.settingsPage)};
        if (pages[HOME] == null || pages[SETTINGS] == null) {
            throw new IllegalStateException("HomeSettingsPager requires both page layouts");
        }
        setAdapter(new PagerAdapter() {
            @Override
            public int getCount() {
                return pages.length;
            }

            @Override
            public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
                return view == object;
            }

            @NonNull
            @Override
            public Object instantiateItem(@NonNull ViewGroup container, int position) {
                View page = pages[position];
                if (page.getParent() == null) container.addView(page);
                return page;
            }

            @Override
            public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
                container.removeView((View) object);
            }
        });
        // No alpha/scale transformer: at a half-width drag each page occupies
        // exactly half the viewport, including each game's in-card controls.
    }
}
