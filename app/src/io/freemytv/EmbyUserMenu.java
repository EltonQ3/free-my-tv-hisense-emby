package io.freemytv;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.*;
import org.json.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;

/** In-place selection of the account whose library and progress the home displays. */
final class EmbyUserMenu {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final ExecutorService AVATARS = Executors.newFixedThreadPool(2);
    private final Activity activity;
    private final Recs recs;
    private final Click click;
    private final Runnable selected;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PopupWindow popup;
    private final LinearLayout root;
    private final LinearLayout list;
    private final TextView heading;
    private final float scale;
    private final int ink, quiet;
    private final int maxHeight;
    private ValueAnimator heightAnimation;
    private boolean closing, immediate, dismissSound = true;
    private volatile boolean open = true;
    private boolean changing;
    private final List<AvatarRequest> avatars = new ArrayList<>();
    private final Rect visibleAvatar = new Rect();
    private int avatarGeneration;
    private static final class AvatarRequest {
        JSONObject config;
        EmbyAccounts.User user;
        AvatarView avatar;
        boolean started;
        int generation;
    }

    EmbyUserMenu(Activity activity, Recs recs, Click click, Runnable selected,
                 Scene scene, float scale, boolean dark, int left, int top, int maxHeight) {
        this.activity = activity; this.recs = recs; this.click = click; this.selected = selected; this.scale = scale;
        this.maxHeight = maxHeight;
        ink = dark ? 0xfff5f5f7 : 0xff24252a; quiet = dark ? 0xffa8a9b0 : 0xff6c6d74;
        root = new LinearLayout(activity) {
            private final RectF glass = new RectF();
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                canvas.save(); canvas.translate(-left, -top);
                glass.set(left, top, left + getWidth(), top + getHeight());
                scene.setOffset(0, 0);
                scene.liquid(canvas, glass, px(22), dark ? 0xff1c1c1e : 0xffffffff,
                        dark ? .70f : .72f, .02f, px(12), px(8), dark ? .45f : .60f, 1);
                canvas.restore();
            }
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                int code = event.getKeyCode();
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_RIGHT) click.move();
                }
                return super.dispatchKeyEvent(event);
            }
        };
        root.setWillNotDraw(false);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(px(12), px(8), px(12), px(12));
        root.setClipChildren(false); root.setClipToPadding(false);
        heading = text(Ui.tr("Account"), 23, quiet); heading.setPadding(px(14), px(12), 0, px(14)); root.addView(heading);
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(false);
        list = new LinearLayout(activity); list.setOrientation(LinearLayout.VERTICAL); scroll.addView(list);
        list.setClipChildren(false); list.setClipToPadding(false);
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::fetchVisibleAvatars);
        root.getViewTreeObserver().addOnScrollChangedListener(this::fetchVisibleAvatars);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        popup = new PopupWindow(root, px(380), Math.min(px(100), maxHeight), true) {
            @Override public void dismiss() {
                if (immediate) super.dismiss();
                else closeWindow(() -> super.dismiss());
            }
        };
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0); popup.setEnterTransition(null); popup.setExitTransition(null);
        popup.setElevation(px(18)); popup.setOnDismissListener(() -> open = false);
        root.setPivotX(px(190)); root.setPivotY(-px(48));
        if (ValueAnimator.areAnimatorsEnabled()) {
            root.setAlpha(0); root.setScaleX(.92f); root.setScaleY(.90f); root.setTranslationY(-px(8));
        }
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.TOP | Gravity.LEFT, left, top);
        root.post(() -> {
            if (!closing) root.animate().alpha(1).scaleX(1).scaleY(1).translationY(0).setDuration(260)
                    .setInterpolator(new PathInterpolator(.22f, 1f, .36f, 1f)).start();
        });
        load();
    }

    private int px(float value) { return Math.max(1, Math.round(value * scale)); }
    private GradientDrawable shape(int color, float radius, int edge) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius);
        if (edge != 0) d.setStroke(px(1), edge); return d;
    }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(activity); t.setText(value); t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size)); t.setSingleLine(true);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); t.setSoundEffectsEnabled(false); return t;
    }

    private void load() {
        heading.setText(Ui.tr("Connecting…")); list.removeAllViews();
        IO.execute(() -> {
            try {
                JSONObject config = EmbyRecs.readConfig(activity);
                JSONArray users = new JSONArray(EmbyRecs.request(config, "/Users"));
                main.post(() -> { if (open) render(config, users); });
            } catch (Exception e) { main.post(() -> { if (open) failed(); }); }
        });
    }

    private void render(JSONObject config, JSONArray users) {
        heading.setText(Ui.tr("RECOMMENDATIONS") + " · " + Ui.tr("Account")); list.removeAllViews(); View current = null, first = null;
        avatars.clear(); avatarGeneration++;
        int rowIndex = 0;
        for (EmbyAccounts.User user : EmbyAccounts.users(users)) {
            String id = user.id, name = user.name;
            boolean active = id.equals(config.optString("user_id"));
            LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(12), 0, px(14), 0); row.setFocusable(true); row.setFocusableInTouchMode(true);
            row.setClickable(true); row.setSoundEffectsEnabled(false);
            row.setDefaultFocusHighlightEnabled(false);
            AvatarView avatar = new AvatarView(name);
            row.addView(avatar, new LinearLayout.LayoutParams(px(36), px(36)));
            if (!user.imageTag.isEmpty()) {
                AvatarRequest request = new AvatarRequest(); request.config = config; request.avatar = avatar;
                request.user = user;
                request.generation = avatarGeneration; avatars.add(request);
            }
            TextView label = text(name, 25, ink); label.setPadding(px(14), 0, 0, 0);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            TextView check = text(active ? "✓" : "", 26, ink); row.addView(check);
            row.setOnFocusChangeListener((v, focused) -> {
                int color = focused ? 0xff202126 : ink;
                row.setBackground(shape(focused ? 0xfff5f5f7 : Color.TRANSPARENT, px(14), 0));
                avatar.color(color); label.setTextColor(color); check.setTextColor(color);
                row.animate().scaleX(focused ? 1.025f : 1).scaleY(focused ? 1.025f : 1).setDuration(180)
                        .setInterpolator(new PathInterpolator(.22f, 1f, .36f, 1f)).start();
            });
            row.setOnClickListener(v -> { click.open(); if (active) dismiss(); else change(config, id, name); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(62)); lp.bottomMargin = px(4); list.addView(row, lp);
            if (ValueAnimator.areAnimatorsEnabled()) {
                row.setAlpha(0); row.setTranslationY(-px(4));
                row.animate().alpha(1).translationY(0).setStartDelay(Math.min(rowIndex, 5) * 14L).setDuration(180)
                        .setInterpolator(new PathInterpolator(.22f, 1f, .36f, 1f)).start();
            }
            if (first == null) first = row; if (active) current = row;
            rowIndex++;
        }
        View focus = current == null ? first : current;
        if (focus == null) { failed(); return; }
        resize(Math.min(maxHeight, px(68 + Math.min(list.getChildCount(), 6) * 66)));
        focus.requestFocus();
    }

    private void fetchVisibleAvatars() {
        if (!open || closing) return;
        for (AvatarRequest request : avatars) {
            if (request.started || !request.avatar.getGlobalVisibleRect(visibleAvatar)) continue;
            request.started = true;
            AVATARS.execute(() -> {
                if (!open) return;
                try {
                    EmbyProfile.Snapshot profile = EmbyProfile.loadUser(request.config, request.user.id, request.user.name, request.user.imageTag);
                    main.post(() -> {
                        if (!open || request.generation != avatarGeneration || profile.image == null) return;
                        request.avatar.image(profile.image);
                    });
                } catch (Exception ignored) { }
            });
        }
    }

    /** Match the home avatar's Canvas drawing, original colors and circular crop. */
    private final class AvatarView extends View {
        private final String initial;
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF target = new RectF();
        private Bitmap image;
        AvatarView(String name) {
            super(activity);
            initial = name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.CENTER); textPaint.setTextSize(px(23)); textPaint.setColor(ink);
            circle.setColor(0x18888888);
        }
        void color(int value) { textPaint.setColor(value); invalidate(); }
        void image(Bitmap value) { image = value; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float x = getWidth() / 2f, y = getHeight() / 2f, radius = Math.min(x, y);
            if (image == null) {
                canvas.drawCircle(x, y, radius, circle);
                canvas.drawText(initial, x, y - (textPaint.ascent() + textPaint.descent()) / 2, textPaint);
            } else {
                target.set(x - radius, y - radius, x + radius, y + radius);
                clip.reset(); clip.addCircle(x, y, radius, Path.Direction.CW);
                canvas.save(); canvas.clipPath(clip); canvas.drawBitmap(image, null, target, imagePaint); canvas.restore();
            }
        }
    }

    private void change(JSONObject config, String id, String name) {
        if (changing || !open) return;
        changing = true; heading.setText(Ui.tr("Connecting…"));
        IO.execute(() -> {
            try {
                JSONObject user = new JSONObject(EmbyRecs.request(config, "/Users/" + id));
                JSONObject current = EmbyRecs.readConfig(activity);
                JSONObject replacement = EmbyAccounts.selection(config, current, id, user);
                if (!open) return;
                EmbyRecs.saveConfig(activity, replacement);
                main.post(() -> {
                    recs.connectionChanged(replacement.optString("user_name"));
                    if (open) { dismiss(); selected.run(); }
                });
            } catch (Exception e) { main.post(() -> { changing = false; if (open) heading.setText(Ui.tr("Connection failed")); }); }
        });
    }

    private void failed() {
        heading.setText(Ui.tr("Connection failed")); list.removeAllViews();
        Button retry = new Button(activity); retry.setText(Ui.tr("Refresh now")); retry.setSoundEffectsEnabled(false);
        retry.setOnClickListener(v -> { click.open(); load(); }); list.addView(retry); retry.requestFocus();
        resize(Math.min(maxHeight, px(150)));
    }

    private void resize(int height) {
        if (heightAnimation != null) heightAnimation.cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) { popup.update(px(380), height); return; }
        heightAnimation = ValueAnimator.ofInt(popup.getHeight(), height);
        heightAnimation.setDuration(240); heightAnimation.setInterpolator(new PathInterpolator(.22f, 1f, .36f, 1f));
        heightAnimation.addUpdateListener(value -> { if (open && !closing) popup.update(px(380), (int) value.getAnimatedValue()); });
        heightAnimation.start();
    }

    private void closeWindow(Runnable finish) {
        if (closing) return;
        closing = true; open = false;
        if (dismissSound) click.back();
        if (heightAnimation != null) heightAnimation.cancel(); root.animate().cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) { finish.run(); return; }
        root.animate().alpha(0).scaleX(.96f).scaleY(.94f).translationY(-px(5)).setDuration(160)
                .setInterpolator(new PathInterpolator(.4f, 0f, 1f, 1f)).withEndAction(finish).start();
    }

    void dismiss() { dismissSound = false; popup.dismiss(); }
    void dismissNow() {
        open = false; immediate = true; closing = true;
        if (heightAnimation != null) heightAnimation.cancel(); root.animate().cancel(); popup.dismiss();
    }
}
