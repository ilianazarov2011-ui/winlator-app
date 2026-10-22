package com.winlator.porthub;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Единый стиль PortHub: цвета, темы, карточки, кнопки. */
public final class Style {

    public static final int BG = 0xFF070A0F;
    public static final int BG2 = 0xFF0C1118;
    public static final int CARD = 0xFF101720;
    public static final int CARD2 = 0xFF16202B;
    public static final int LINE = 0xFF1E2A38;
    public static final int TEXT = 0xFFE8EEF5;
    public static final int DIM = 0xFF7C8794;
    public static final int OK = 0xFF22C55E;
    public static final int WARN = 0xFFF59E0B;
    public static final int BAD = 0xFFEF4444;

    public static int ACCENT = 0xFF4F8BF7;
    public static int ACCENT2 = 0xFF8B5CF6;
    public static String THEME = "blue";

    public static final String[] THEMES = {"blue", "violet", "green", "orange", "red"};
    public static final String[] THEME_LABELS = {"Синяя", "Фиолет", "Зелёная", "Оранж", "Красная"};

    public static void applyTheme(String id) {
        THEME = (id == null || id.isEmpty()) ? "blue" : id;
        if (THEME.equals("violet")) { ACCENT = 0xFF8B5CF6; ACCENT2 = 0xFF6366F1; }
        else if (THEME.equals("green")) { ACCENT = 0xFF22C55E; ACCENT2 = 0xFF0EA5E9; }
        else if (THEME.equals("orange")) { ACCENT = 0xFFF59E0B; ACCENT2 = 0xFFEF4444; }
        else if (THEME.equals("red")) { ACCENT = 0xFFEF4444; ACCENT2 = 0xFFB91C1C; }
        else { ACCENT = 0xFF4F8BF7; ACCENT2 = 0xFF8B5CF6; }
    }

    public static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    public static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private final Context ctx;

    public Style(Context ctx) { this.ctx = ctx; }

    public int dp(int v) { return (int) (v * ctx.getResources().getDisplayMetrics().density); }

    public GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    public GradientDrawable roundStroke(int color, int strokeColor, int radiusDp) {
        GradientDrawable g = round(color, radiusDp);
        g.setStroke(dp(1), strokeColor);
        return g;
    }

    public Drawable press(Drawable base) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null);
    }

    public TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public TextView mono(String value, float size, int color) {
        TextView t = text(value, size, color, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }

    public LinearLayout card() {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundStroke(CARD, LINE, 18));
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    public TextView button(String label, int color, float size, Runnable onClick) {
        TextView b = text(label, size, 0xFFFFFFFF, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(14), dp(14), dp(14));
        b.setBackground(press(round(color, 14)));
        b.setClickable(true);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    public TextView big(String label, int color, Runnable onClick) { return button(label, color, 17f, onClick); }

    public TextView small(String label, Runnable onClick) {
        TextView b = text(label, 12, TEXT, false);
        b.setPadding(dp(10), dp(7), dp(10), dp(7));
        b.setBackground(press(round(CARD2, 10)));
        b.setClickable(true);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    public TextView chip(String label, boolean selected) {
        TextView t = text(label, 12, selected ? 0xFFFFFFFF : TEXT, selected);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setBackground(press(round(selected ? ACCENT : CARD2, 20)));
        t.setClickable(true);
        return t;
    }

    public LinearLayout row() {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    public View gap(int heightDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(heightDp)));
        return v;
    }

    public View line() {
        View v = new View(ctx);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(1));
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }
                          }
