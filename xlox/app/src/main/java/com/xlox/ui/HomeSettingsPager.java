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

    public HomeSettingsPager(Context context) {
        this(context, null);
    }

    public HomeSettingsPager(Context context, AttributeSet attrs) {
        super(context, attrs);
        // These English navigation labels always keep Home left / Settings right.
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LayoutInflater inflater = LayoutInflater.from(context);
        final View[] pages = {
                inflater.inflate(R.layout.page_home, this, false),
                inflater.inflate(R.layout.page_settings, this, false)
        };
        // Attach both now so MAct can bind IDs during onCreate, before ViewPager's
        // first measure/populate. instantiateItem below is deliberately idempotent.
        for (View page : pages) addView(page);
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
        setOffscreenPageLimit(1);
        // No alpha/scale transformer: at a half-width drag each page occupies
        // exactly half the viewport, including Home's own Launch / Stop bar.
    }
}
