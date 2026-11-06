package com.jingjump.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

final class UiKit {
    private UiKit() {
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context context, CharSequence value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        view.setLineSpacing(0f, 1.08f);
        return view;
    }

    static TextView button(Context context, String label, boolean primary) {
        TextView button = text(context, label, 15f,
                context.getColor(primary ? R.color.white : R.color.ink), true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 50));
        button.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        button.setBackgroundResource(primary ? R.drawable.bg_primary_button : R.drawable.bg_secondary_button);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    static GradientDrawable rounded(Context context, int fillColor, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(context, Math.round(radiusDp)));
        return drawable;
    }

    static GradientDrawable bordered(
            Context context,
            int fillColor,
            int strokeColor,
            float radiusDp
    ) {
        GradientDrawable drawable = rounded(context, fillColor, radiusDp);
        drawable.setStroke(dp(context, 1), strokeColor);
        return drawable;
    }

    static LinearLayout vertical(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout horizontal(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    static LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    static LinearLayout.LayoutParams weighted(int width, int height, float weight) {
        return new LinearLayout.LayoutParams(width, height, weight);
    }

    static LinearLayout.LayoutParams margins(
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom
    ) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    static void addSpace(LinearLayout parent, Context context, int heightDp) {
        View spacer = new View(context);
        parent.addView(spacer, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)));
    }

    static View divider(Context context, int color) {
        View divider = new View(context);
        divider.setBackgroundColor(color);
        return divider;
    }

    static int alphaColor(int color, float alpha) {
        int a = Math.round(Color.alpha(color) * alpha);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }
}
