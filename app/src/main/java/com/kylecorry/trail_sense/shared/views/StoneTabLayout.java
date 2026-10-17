package com.kylecorry.trail_sense.shared.views;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.tabs.TabLayout;

// The same file in every XML app of this shop. It draws a TabLayout the way StoneTabs.kt draws
// tabs in the Compose apps, which is Messaging's All / Groups / Unknown row.

/**
 * A TabLayout drawn as StoneTabs: the chosen tab a black stone (4dp corners) with its label in
 * white, the others a plain label in black, all of them bold, set from the left at their own
 * widths, and a 1dp rule beneath the row. A row that would not fit at 20sp steps down to 16sp
 * with tighter stones, as StoneTabs does; one that still would not (six tabs) goes to 14sp, the
 * floor, with the tightest stones, rather than hiding its last tab off the edge.
 *
 * Call {@link #apply(TabLayout)} once the tabs exist (after TabLayoutMediator.attach()).
 */
public final class StoneTabLayout {

    private StoneTabLayout() {
    }

    public static void apply(TabLayout tabs) {
        Context c = tabs.getContext();
        tabs.setTabMode(TabLayout.MODE_SCROLLABLE);
        tabs.setTabGravity(TabLayout.GRAVITY_START);
        tabs.setSelectedTabIndicator(null);
        tabs.setSelectedTabIndicatorHeight(0);
        tabs.setTabRippleColor(null);
        tabs.setHorizontalScrollBarEnabled(false);

        // White, with the 1dp rule along the bottom.
        LayerDrawable ground = new LayerDrawable(new Drawable[]{
                new ColorDrawable(Color.WHITE), new ColorDrawable(Color.BLACK)});
        ground.setLayerGravity(1, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        ground.setLayerHeight(1, px(c, 1));
        tabs.setBackground(ground);

        // StoneTabs: a 56dp row, 12dp in from each side, 6dp between stones, then the rule.
        tabs.setPaddingRelative(px(c, 9), px(c, 4), px(c, 9), px(c, 5));

        for (int i = 0; i < tabs.getTabCount(); i++) {
            TabLayout.Tab tab = tabs.getTabAt(i);
            if (tab == null) continue;
            TextView label = new TextView(c);
            // The app's titleMedium (Lato, as MMD's), then bold, sized below.
            TypedValue appearance = new TypedValue();
            if (c.getTheme().resolveAttribute(
                    com.google.android.material.R.attr.textAppearanceTitleMedium, appearance, true)) {
                label.setTextAppearance(appearance.resourceId);
            }
            label.setText(tab.getText());
            label.setTypeface(Typeface.create(lato(c, label.getTypeface()), Typeface.BOLD));
            label.setTextColor(new ColorStateList(
                    new int[][]{{android.R.attr.state_selected}, {}},
                    new int[]{Color.WHITE, Color.BLACK}));
            GradientDrawable stone = new GradientDrawable();
            stone.setColor(Color.BLACK);
            stone.setCornerRadius(px(c, 4));
            StateListDrawable back = new StateListDrawable();
            back.addState(new int[]{android.R.attr.state_selected}, stone);
            back.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
            label.setBackground(back);
            label.setGravity(Gravity.CENTER);
            label.setMinHeight(px(c, 40));
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setSelected(tab.isSelected());
            tab.setCustomView(label);
            tab.view.setPaddingRelative(px(c, 3), 0, px(c, 3), 0);
            tab.view.setMinimumWidth(0);
            tab.view.setBackground(null);
        }
        size(tabs, 20, 14);
        tabs.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (r - l == or - ol) return;
                fit(tabs);
            }
        });
        tabs.post(() -> fit(tabs));
        // A row that fits stays put: TabLayout scrolls the chosen tab towards the middle even
        // when the last tab already ends short of the edge, which cut the first label off.
        tabs.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            if (x != 0 && lastTabEnd(tabs) <= tabs.getWidth() - tabs.getPaddingLeft() - tabs.getPaddingRight()) {
                tabs.scrollTo(0, 0);
            }
        });
    }

    private static int lastTabEnd(TabLayout tabs) {
        TabLayout.Tab last = tabs.getTabAt(tabs.getTabCount() - 1);
        return last == null ? 0 : last.view.getRight();
    }

    /** titleMedium bold with 14dp stones if the whole row fits, else titleSmall with 8dp, else 14sp with 4dp. */
    private static void fit(TabLayout tabs) {
        Context c = tabs.getContext();
        int width = tabs.getWidth();
        if (width == 0) return;
        int n = tabs.getTabCount();
        int gaps = Math.max(0, n - 1) * 6 + 24;
        if (rowWidth(tabs, 20) + px(c, n * 28 + gaps) <= width) size(tabs, 20, 14);
        else if (rowWidth(tabs, 16) + px(c, n * 16 + gaps) <= width) size(tabs, 16, 8);
        else size(tabs, 14, 4);
    }

    private static float rowWidth(TabLayout tabs, int sp) {
        Paint paint = new Paint();
        paint.setTypeface(Typeface.create(firstLabelTypeface(tabs), Typeface.BOLD));
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                tabs.getResources().getDisplayMetrics()));
        float text = 0;
        for (int i = 0; i < tabs.getTabCount(); i++) {
            TabLayout.Tab tab = tabs.getTabAt(i);
            if (tab != null && tab.getText() != null) text += paint.measureText(tab.getText().toString());
        }
        return text;
    }

    /** The theme's own face (Lato, set as android:fontFamily); else what the text has. */
    private static Typeface lato(Context c, Typeface fallback) {
        TypedValue family = new TypedValue();
        if (c.getTheme().resolveAttribute(android.R.attr.fontFamily, family, true)
                && family.resourceId != 0) {
            try {
                Typeface t = androidx.core.content.res.ResourcesCompat.getFont(c, family.resourceId);
                if (t != null) return t;
            } catch (RuntimeException ignored) {
                // Not a font resource (a system family name): the text's own face will do.
            }
        }
        return fallback != null ? fallback : Typeface.DEFAULT;
    }

    private static Typeface firstLabelTypeface(TabLayout tabs) {
        TabLayout.Tab tab = tabs.getTabAt(0);
        if (tab != null && tab.getCustomView() instanceof TextView) {
            Typeface t = ((TextView) tab.getCustomView()).getTypeface();
            if (t != null) return t;
        }
        return Typeface.DEFAULT;
    }

    private static void size(TabLayout tabs, int sp, int padDp) {
        Context c = tabs.getContext();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            TabLayout.Tab tab = tabs.getTabAt(i);
            if (tab == null || !(tab.getCustomView() instanceof TextView)) continue;
            TextView label = (TextView) tab.getCustomView();
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            // A 40dp stone, as StoneTabs: the line plus what is left of 40dp above and below.
            int line = label.getLineHeight();
            int vertical = Math.max(0, (px(c, 40) - line) / 2);
            label.setPaddingRelative(px(c, padDp), vertical, px(c, padDp), vertical);
            tab.view.setMinimumWidth(0);
        }
    }

    private static int px(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }
}
