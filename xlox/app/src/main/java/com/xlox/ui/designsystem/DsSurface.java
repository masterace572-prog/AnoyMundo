package com.xlox.ui.designsystem;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import com.xlox.R;

/**
 * Vertical container with a bespoke top-lit matte surface. Use dsVariant to
 * pick card / tile / recessed / pill / banner. Children are laid out exactly
 * as in a vertical LinearLayout, so no layout semantics change.
 */
public class DsSurface extends LinearLayout {

    private int variant = TopLitDrawable.CARD;

    public DsSurface(Context context) {
        super(context);
        init(context, null);
    }

    public DsSurface(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        // Respect an explicit android:orientation; default to vertical.
        int orientation = attrs == null ? -1 : attrs.getAttributeIntValue(
                "http://schemas.android.com/apk/res/android", "orientation", -1);
        if (orientation < 0) setOrientation(VERTICAL);
        setClipToPadding(false);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.DsSurface);
            variant = a.getInt(R.styleable.DsSurface_dsVariant, TopLitDrawable.CARD);
            a.recycle();
        }
        setBackground(new TopLitDrawable(context, variant));
    }

    public void setVariant(int value) {
        variant = value;
        setBackground(new TopLitDrawable(getContext(), variant));
    }

    public int getVariant() { return variant; }
}
