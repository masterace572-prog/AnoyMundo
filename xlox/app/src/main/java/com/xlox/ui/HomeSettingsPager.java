package com.xlox.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.xlox.R;

/** Two retained, opaque pages using ViewPager's real horizontal drag and settle. */
public final class HomeSettingsPager extends ViewPager {

    public static final int HOME = 0;
    public static final int SETTINGS = 1;

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
        // These English navigation labels always keep Home left / Settings right.
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        setOffscreenPageLimit(1);
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
        // exactly half the viewport, including Home's own Launch / Stop bar.
    }
}
