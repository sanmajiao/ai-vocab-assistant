package com.sanmajiao.aivocab;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.Button;

/** Shared native-view palette; no remote assets or additional UI dependencies. */
final class UiTheme {
    static final int BACKGROUND = Color.rgb(247, 248, 244);
    static final int SURFACE = Color.WHITE;
    static final int INK = Color.rgb(28, 49, 57);
    static final int MUTED = Color.rgb(93, 112, 116);
    static final int TEAL = Color.rgb(17, 126, 114);
    static final int INDIGO = Color.rgb(73, 80, 159);
    static final int SOFT_TEAL = Color.rgb(225, 242, 235);
    static final int SOFT_INDIGO = Color.rgb(235, 237, 250);
    static final int BORDER = Color.rgb(220, 230, 225);

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable panel(Context context, int color, int radius, boolean border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radius));
        if (border) drawable.setStroke(dp(context, 1), BORDER);
        return drawable;
    }

    static void button(Button button, boolean primary) {
        Context context = button.getContext();
        int normal = primary ? TEAL : SOFT_INDIGO;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.rgb(184, 216, 207)),
                panel(context, normal, 14, false), null));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{MUTED, primary ? Color.WHITE : INDIGO}));
        button.setAllCaps(false);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8));
        button.setStateListAnimator(null);
    }
}
