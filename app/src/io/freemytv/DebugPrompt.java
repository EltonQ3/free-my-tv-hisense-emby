package io.freemytv;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/** A small glass prompt above the stock app. Enabling succeeds before dismissing and continuing the original click. */
final class DebugPrompt {
    private final PopupWindow popup;
    private final LinearLayout root;
    private final float scale;
    private boolean active = true, closing, immediate, changing;

    DebugPrompt(Activity activity, Click click, Scene scene, float scale, boolean dark, RectF anchor,
                int screenWidth, int screenHeight, Runnable opened) {
        this.scale = scale;
        int width = Math.min(px(440), screenWidth - px(48)), height = px(236);
        int left = Math.max(px(24), Math.min(screenWidth - width - px(24), Math.round(anchor.centerX() - width / 2f)));
        int top = Math.max(px(24), Math.min(screenHeight - height - px(24), Math.round(anchor.top - height - px(18))));
        int ink = dark ? 0xfff5f5f7 : 0xff24252a;
        root = new LinearLayout(activity) {
            private final RectF glass = new RectF();
            private final Paint panel = panel(dark, height);
            private final Paint rim = rim(dark);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                // Settings use their quiet native gradients; the home screen keeps its photographic glass.
                if (scene == null) {
                    glass.set(.5f, .5f, getWidth() - .5f, getHeight() - .5f);
                    canvas.drawRoundRect(glass, px(22), px(22), panel);
                    canvas.drawRoundRect(glass, px(22), px(22), rim);
                    return;
                }
                canvas.save(); canvas.translate(-left, -top); glass.set(left, top, left + getWidth(), top + getHeight());
                scene.setOffset(0, 0);
                scene.liquid(canvas, glass, px(22), dark ? 0xff1c1c1e : 0xffffffff,
                        dark ? .80f : .82f, .02f, px(12), px(8), dark ? .45f : .60f, 1);
                canvas.restore();
            }
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() >= KeyEvent.KEYCODE_DPAD_UP
                        && event.getKeyCode() <= KeyEvent.KEYCODE_DPAD_RIGHT) click.move();
                return super.dispatchKeyEvent(event);
            }
        };
        root.setWillNotDraw(false); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(22), px(20), px(22), px(18));
        TextView title = text(activity, Ui.trf("Open {app}", "app", "聚好看"), 26, ink); root.addView(title);
        TextView hint = text(activity, Ui.trf("Enable debugging to open {app}.", "app", "聚好看"), 20, dark ? 0xffbfc0c6 : 0xff666970);
        hint.setSingleLine(false); hint.setMaxLines(2); hint.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(hint, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView button = text(activity, Ui.tr("Enable debugging"), 24, ink);
        button.setGravity(Gravity.CENTER); button.setFocusable(true); button.setFocusableInTouchMode(true);
        button.setClickable(true); button.setDefaultFocusHighlightEnabled(false); button.setSoundEffectsEnabled(false);
        button.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable shape = new GradientDrawable(); shape.setColor(focused ? 0xfff5f5f7 : dark ? 0x22ffffff : 0x18000000);
            shape.setCornerRadius(px(14)); button.setBackground(shape); button.setTextColor(focused ? 0xff202126 : ink);
            button.animate().scaleX(focused ? 1.015f : 1).scaleY(focused ? 1.015f : 1).setDuration(160).start();
        });
        root.addView(button, new LinearLayout.LayoutParams(-1, px(58)));
        popup = new PopupWindow(root, width, height, true) {
            @Override public void dismiss() { if (immediate) super.dismiss(); else close(null); }
        };
        button.setOnClickListener(v -> {
            if (changing || !active) return;
            changing = true; click.open(); button.setText(Ui.tr("Connecting…"));
            DebugControls.set(activity, true, message -> { if (active) hint.setText(message); }, success -> {
                if (!active) return;
                if (success) close(opened);
                else { changing = false; button.setText(Ui.tr("Enable debugging")); }
            });
        });
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); popup.setOutsideTouchable(true);
        popup.setElevation(px(18)); popup.setAnimationStyle(0); popup.setEnterTransition(null); popup.setExitTransition(null);
        popup.setOnDismissListener(() -> active = false);
        root.setPivotX(width / 2f); root.setPivotY(height);
        root.setAlpha(0); root.setScaleX(.94f); root.setScaleY(.94f); root.setTranslationY(px(6));
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.TOP | Gravity.LEFT, left, top);
        root.post(() -> {
            if (!active) return;
            root.animate().alpha(1).scaleX(1).scaleY(1).translationY(0).setDuration(240)
                    .setInterpolator(new PathInterpolator(.22f, 1f, .36f, 1f)).start();
            button.requestFocus();
        });
    }
    private static Paint panel(boolean dark, int height) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, height,
                dark ? 0xff343a46 : 0xfff8f9fc, dark ? 0xff252b35 : 0xffe7ebf2, Shader.TileMode.CLAMP));
        return p;
    }
    private static Paint rim(boolean dark) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1);
        p.setColor(dark ? 0x33ffffff : 0x26000000); return p;
    }
    private int px(float n) { return Math.max(1, Math.round(n * scale)); }
    private TextView text(Activity a, String label, int size, int ink) {
        TextView t = new TextView(a); t.setText(label); t.setTextColor(ink);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size)); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setSoundEffectsEnabled(false); return t;
    }
    private void close(Runnable after) {
        if (closing) return;
        closing = true; active = false;
        root.animate().cancel(); root.animate().alpha(0).scaleX(.97f).scaleY(.97f).setDuration(140).withEndAction(() -> {
            immediate = true; popup.dismiss(); if (after != null) after.run();
        }).start();
    }
    void dismissNow() {
        active = false; closing = true; immediate = true; root.animate().cancel(); popup.dismiss();
    }
}
