package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A layout-specific illustration: characters on the left, quiet meadow behind the settings on the right. */
final class SettingsBackdrop {
    private static final String ASSET = "wallpapers/chiikawa-settings-v2.png";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Bitmap cached;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF frame = new RectF();
    private final RectF panel = new RectF();
    private final Paint panelFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Recording panelLayer = Recording.create("settings-panel");
    private Bitmap image;
    private boolean classic;
    private SettingsTheme theme=SettingsTheme.CHIIKAWA;
    private final IllustratedSettingsArt illustratedArt=new IllustratedSettingsArt();
    private final ClassicSettingsArt classicArt = new ClassicSettingsArt();
    private long readyAt;
    private int width, height, generation;

    void setSize(int w, int h) {
        width = Math.max(1, w); height = Math.max(1, h);
        classicArt.setSize(width, height);
        illustratedArt.setSize(width, height);
        float k = width / 1920f;
        panel.set(960 * k, 82 * k, 1864 * k, height - 16 * k);
        panelFill.setColor(0xDEFFFEF3);
        panelBorder.setColor(0xFFBED0A7); panelBorder.setStyle(Paint.Style.STROKE); panelBorder.setStrokeWidth(1.25f * k);
        panelShadow.setColor(0x30465B30); panelShadow.setMaskFilter(new BlurMaskFilter(24 * k, BlurMaskFilter.Blur.NORMAL));
        recordShadow();
    }

    private void recordShadow() {
        // The window can lose recorded GPU resources while stopped. Re-record once when it resumes.
        panelLayer.setPosition(0, 0, width, height);
        panelLayer.setUseCompositingLayer(true, layerPaint);
        Canvas layer = panelLayer.beginRecording(width, height);
        float k = width / 1920f, radius = 36 * k;
        try {
            panel.offset(0, 10 * k); layer.drawRoundRect(panel, radius, radius, panelShadow);
        } finally { panel.offset(0, -10 * k); panelLayer.endRecording(); }
    }

    void load(Context context, Runnable changed) {
        if (width == 0 || height == 0) return;
        theme=SettingsTheme.current(); classic=theme.classic;
        if(theme.illustrated) { generation++; illustratedArt.load(context,theme,changed); return; }
        if (classic) { generation++; classicArt.resume(); changed.run(); return; }
        recordShadow();
        int request = ++generation, w = width, h = height;
        Context app = context.getApplicationContext();
        if (cached != null) { image = cached; readyAt = 0; changed.run(); return; }
        IO.execute(() -> {
            Bitmap bitmap = cached;
            if (bitmap == null) try {
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                try (InputStream in = app.getAssets().open(ASSET)) { BitmapFactory.decodeStream(in, null, bounds); }
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
                while (bounds.outWidth / (options.inSampleSize * 2) >= w && bounds.outHeight / (options.inSampleSize * 2) >= h)
                    options.inSampleSize *= 2;
                try (InputStream in = app.getAssets().open(ASSET)) { bitmap = BitmapFactory.decodeStream(in, null, options); }
                if (bitmap != null) { bitmap = Backdrop.gpu(bitmap); cached = bitmap; }
            } catch (Exception e) { AppLog.w("Settings illustration unavailable"); }
            Bitmap result = bitmap;
            MAIN.post(() -> {
                if (generation != request) return;
                image = result; readyAt = SystemClock.uptimeMillis(); changed.run();
            });
        });
    }

    String status() { return theme.illustrated ? illustratedArt.status() : classic ? classicArt.status() : "chiikawa"; }
    void pause() { generation++; illustratedArt.pause(); }

    boolean animating(long uptime) { return theme.illustrated ? illustratedArt.animating(uptime) : classic ? classicArt.needsFrame(uptime) : image != null && readyAt != 0 && uptime - readyAt < 350; }

    void draw(Canvas canvas, long uptime) {
        if(theme.illustrated) { illustratedArt.draw(canvas,uptime);return; }
        if (classic) { classicArt.draw(canvas, uptime); return; }
        canvas.drawColor(0xffe4f2ce);
        if (width == 0 || height == 0) return;
        if (image != null) {
            // Fixed framing keeps every character clear of the menu and avoids cropping ears or moving behind text.
            frame.set(0, 0, width, height);
            paint.setAlpha(readyAt == 0 ? 255 : Math.round(255 * Math.min(1f, (uptime - readyAt) / 350f)));
            canvas.drawBitmap(image, null, frame, paint);
        }
        panelLayer.draw(canvas);
        // Essential contrast is cheap geometry, independent of optional GPU caches.
        float radius = 36 * width / 1920f;
        canvas.drawRoundRect(panel, radius, radius, panelFill);
        canvas.drawRoundRect(panel, radius, radius, panelBorder);
    }
}
