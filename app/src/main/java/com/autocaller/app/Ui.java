package com.autocaller.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Small helpers for building the screens in code (the app has no XML layouts). */
final class Ui {

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static final int WHITE = 0xFFFFFFFF;
    static final int BG = 0xFFF3F5F4;
    static final int INK = 0xFF14201B;
    static final int MUTED = 0xFF5F6F68;
    static final int LINE = 0xFFDDE4E0;
    static final int PRIMARY = 0xFF0B6E4F;
    static final int GREEN_BG = 0xFFE3F4EC;
    static final int AMBER = 0xFFB45309;
    static final int AMBER_BG = 0xFFFEF3C7;
    static final int RED = 0xFFB3261E;
    static final int RED_BG = 0xFFFDECEA;
    static final int BLUE = 0xFF1D4ED8;
    static final int SOFT = 0xFFE7ECE9;

    interface Pick {
        void picked(int index);
    }

    static final class Screen {
        LinearLayout root;
        LinearLayout bar;
        LinearLayout body;
    }

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    /** Layout params for a child that shares a row equally, with a left margin in dp. */
    static LinearLayout.LayoutParams share(Context c, float leftMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, WRAP, 1f);
        p.setMargins(dp(c, leftMarginDp), 0, 0, 0);
        return p;
    }

    /** Full-width layout params with a top margin in dp. */
    static LinearLayout.LayoutParams below(Context c, float topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, WRAP);
        p.setMargins(0, dp(c, topMarginDp), 0, 0);
        return p;
    }

    static GradientDrawable shape(int color, float radiusPx, int strokeColor, int strokeWidthPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        if (strokeWidthPx > 0) g.setStroke(strokeWidthPx, strokeColor);
        return g;
    }

    static Drawable pressable(Context c, int color, float radiusDp) {
        GradientDrawable g = shape(color, dp(c, radiusDp), 0, 0);
        return new RippleDrawable(ColorStateList.valueOf(0x33000000), g, null);
    }

    static Button button(Context c, CharSequence label, int bg, int fg) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(pressable(c, bg, 10));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        int p = dp(c, 14);
        b.setPadding(p, 0, p, 0);
        return b;
    }

    static Button primary(Context c, CharSequence label) {
        return button(c, label, PRIMARY, WHITE);
    }

    static Button secondary(Context c, CharSequence label) {
        return button(c, label, SOFT, INK);
    }

    static Button danger(Context c, CharSequence label) {
        return button(c, label, RED, WHITE);
    }

    /** White button with a thin border, for use directly on the page background. */
    static Button outline(Context c, CharSequence label) {
        Button b = button(c, label, WHITE, INK);
        b.setBackground(outlineBg(c));
        return b;
    }

    static Drawable outlineBg(Context c) {
        return new RippleDrawable(ColorStateList.valueOf(0x22000000),
                shape(WHITE, dp(c, 10), LINE, dp(c, 1)), null);
    }

    static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.4f);
    }

    static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(shape(WHITE, dp(c, 14), LINE, dp(c, 1)));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.setMargins(0, 0, 0, dp(c, 12));
        l.setLayoutParams(lp);
        return l;
    }

    /** Standard screen: coloured title bar on top, body underneath (scrolling or fixed). */
    static Screen screen(final Activity a, String title, boolean back, boolean scroll) {
        Screen s = new Screen();
        s.root = col(a);
        s.root.setBackgroundColor(BG);

        s.bar = row(a);
        s.bar.setBackgroundColor(PRIMARY);
        s.bar.setPadding(dp(a, back ? 4 : 16), 0, dp(a, 6), 0);
        if (back) {
            TextView arrow = text(a, "←", 24, WHITE, false);
            arrow.setGravity(Gravity.CENTER);
            arrow.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
                    shape(WHITE, dp(a, 24), 0, 0)));
            arrow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.finish();
                }
            });
            s.bar.addView(arrow, lp(dp(a, 48), dp(a, 48)));
        }
        TextView t = text(a, title, 20, WHITE, true);
        t.setSingleLine(true);
        s.bar.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        s.root.addView(s.bar, lp(MATCH, dp(a, 56)));

        s.body = col(a);
        int p = dp(a, 14);
        s.body.setPadding(p, p, p, p);
        if (scroll) {
            ScrollView sv = new ScrollView(a);
            sv.setFillViewport(true);
            sv.addView(s.body, new ViewGroup.LayoutParams(MATCH, WRAP));
            s.root.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        } else {
            s.root.addView(s.body, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        }
        a.setContentView(s.root);
        return s;
    }

    /** Adds a text button on the right of the title bar. */
    static TextView barAction(Screen s, String label, View.OnClickListener onClick) {
        Context c = s.bar.getContext();
        TextView t = text(c, label, 14, WHITE, true);
        t.setGravity(Gravity.CENTER);
        int p = dp(c, 12);
        t.setPadding(p, 0, p, 0);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
                shape(WHITE, dp(c, 8), 0, 0)));
        t.setOnClickListener(onClick);
        s.bar.addView(t, lp(WRAP, dp(c, 40)));
        return t;
    }

    static void toast(Context c, String message) {
        Toast.makeText(c, message, Toast.LENGTH_LONG).show();
    }

    static void info(Context c, String title, String message) {
        try {
            new AlertDialog.Builder(c).setTitle(title).setMessage(message)
                    .setPositiveButton("OK", null).show();
        } catch (Exception e) {
            Logx.err("dialog", e);
        }
    }

    static void confirm(Context c, String title, String message, String ok, final Runnable onOk) {
        try {
            new AlertDialog.Builder(c).setTitle(title).setMessage(message)
                    .setPositiveButton(ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            onOk.run();
                        }
                    })
                    .setNegativeButton("Cancel", null).show();
        } catch (Exception e) {
            Logx.err("dialog", e);
        }
    }

    /** Single-choice list dialog; closes as soon as a row is tapped. */
    static void choose(Context c, String title, String[] items, int checked, final Pick pick) {
        try {
            new AlertDialog.Builder(c).setTitle(title)
                    .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            d.dismiss();
                            pick.picked(which);
                        }
                    })
                    .setNegativeButton("Cancel", null).show();
        } catch (Exception e) {
            Logx.err("dialog", e);
        }
    }

    /** Plain action list dialog (no radio buttons). */
    static void menu(Context c, String title, String[] items, final Pick pick) {
        try {
            new AlertDialog.Builder(c).setTitle(title)
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            pick.picked(which);
                        }
                    })
                    .setNegativeButton("Cancel", null).show();
        } catch (Exception e) {
            Logx.err("dialog", e);
        }
    }
}
