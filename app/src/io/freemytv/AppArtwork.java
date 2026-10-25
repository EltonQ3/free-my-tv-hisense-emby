package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.content.pm.ApplicationInfo;

/** Full landscape artwork, using each installed app's original motifs. Built only in HomeApps' bitmap cache. */
final class AppArtwork {
    private static final float CHINESE_WORDMARK_SIZE = 46;
    private static final String[] UTILITIES = {
        "com.hisense.multiscreen.screenhelper", "com.hisense.singlelisten", "com.hisense.systeminfo",
        "com.hisense.hitv.hicloud.account", "com.hisense.tv.weather", "com.hisense.vidaaassistant",
        "com.jamdeo.screensaver", "com.android.wechathci", "com.jamdeo.calendar", "com.vision.aibook",
        "com.hisense.aiot", "com.hisense.electronictutorial", "com.hispeech.ai.area",
        "com.hisense.tv.mirror", "com.android.tv.settings"
    };
    private AppArtwork() { }

    static boolean supports(String pkg) {
        if ("com.jamdeo.tv.mediacenter".equals(pkg) || "com.github.kr328.clash".equals(pkg)
                || "com.jamdeo.tv.vod".equals(pkg) || "io.freemytv".equals(pkg)) return true;
        for (String utility : UTILITIES) if (utility.equals(pkg)) return true;
        return false;
    }

    static Bitmap tile(Context context, String pkg, int width, int height) {
        if (!supports(pkg)) return null;
        Bitmap bitmap = Bitmap.createBitmap(width * 2, height * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        c.scale(bitmap.getWidth() / 384f, bitmap.getHeight() / 216f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if ("com.jamdeo.tv.mediacenter".equals(pkg)) media(c, p);
        else if ("com.github.kr328.clash".equals(pkg)) {
            Bitmap original = originalClash(context);
            if (original == null) { bitmap.recycle(); return null; }
            clash(c, p, original); original.recycle();
        }
        else if ("com.jamdeo.tv.vod".equals(pkg)) stock(c, p);
        else if ("io.freemytv".equals(pkg)) freeMyTv(c, p);
        else utility(context, c, p, pkg);
        bitmap.setHasMipMap(true);
        return bitmap;
    }

    /** New apps without TV art still get a readable brand card rather than a tiny icon in a black frame. */
    static Bitmap fallback(Context context, String pkg, int width, int height) {
        Bitmap source = originalIcon(context, pkg, true);
        Bitmap bitmap = Bitmap.createBitmap(width * 2, height * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap); c.scale(bitmap.getWidth() / 384f, bitmap.getHeight() / 216f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int left = 512, top = 512, right = -1, bottom = -1;
        long red = 0, green = 0, blue = 0; int count = 0;
        if (source != null) {
            int[] pixels = new int[512 * 512]; source.getPixels(pixels, 0, 512, 0, 0, 512, 512);
            for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) {
                int pixel = pixels[y * 512 + x];
                if ((pixel >>> 24) < 32) continue;
                left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y);
                if (x % 8 != 0 || y % 8 != 0) continue;
                int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
                if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 35) continue;
                red += r; green += g; blue += b; count++;
            }
        }
        float[] hsv = {215, .35f, .40f};
        if (count > 0) android.graphics.Color.RGBToHSV((int)(red / count), (int)(green / count), (int)(blue / count), hsv);
        hsv[1] = Math.max(.25f, Math.min(.65f, hsv[1])); hsv[2] = .42f;
        int first = android.graphics.Color.HSVToColor(hsv); hsv[2] = .25f;
        background(c, p, first, android.graphics.Color.HSVToColor(hsv));
        if (source != null) {
            if (right >= left && bottom >= top) {
                RectF bounds = new RectF(left, top, right + 1, bottom + 1);
                float fit = Math.min(115f / bounds.width(), 126f / bounds.height());
                float w = bounds.width() * fit, h = bounds.height() * fit;
                c.save(); c.clipRect(100 - w / 2, 108 - h / 2, 100 + w / 2, 108 + h / 2);
                c.translate(100 - bounds.centerX() * fit, 108 - bounds.centerY() * fit); c.scale(fit, fit);
                c.drawBitmap(source, 0, 0, p); c.restore();
            }
            source.recycle();
        }
        String title = pkg;
        try { title = context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception ignored) { }
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); p.setTextSize(42);
        while (p.measureText(title) > 188 && p.getTextSize() > 26) p.setTextSize(p.getTextSize() - 1);
        title = android.text.TextUtils.ellipsize(title, new android.text.TextPaint(p), 188, android.text.TextUtils.TruncateAt.END).toString();
        wordmark(c, p, title, 174, p.getTextSize(), 0xfff7f9ff, true);
        bitmap.setHasMipMap(true); return bitmap;
    }

    private static void utility(Context context, Canvas c, Paint p, String pkg) {
        int index = -1;
        for (int i = 0; i < UTILITIES.length; i++) if (UTILITIES[i].equals(pkg)) { index = i; break; }
        // The original icon's main object is lifted out of its rounded-square background.
        switch (index) {
            case 0:
                background(c, p, 0xff68c9f5, 0xff147cda);
                object(context, c, p, pkg, rounded(76, 110, 435, 404, 37), new RectF(76, 110, 435, 404), 103, 108, 122, 116);
                wordmark(c, p, "Hi投屏", 184, 46, 0xffffffff, true); break;
            case 1:
                background(c, p, 0xff8a4bcc, 0xff4b4fb4);
                object(context, c, p, pkg, oval(57, 57, 459, 459), new RectF(57, 57, 459, 459), 103, 108, 129, 129);
                wordmark(c, p, "Hi音乐", 184, 46, 0xffffffff, true); break;
            case 2:
                background(c, p, 0xffeef5e9, 0xffdae9ca);
                androidInfo(c, p); stacked(c, p, "System", "Info", 185, 40, 0xff3d612e); break;
            case 3:
                background(c, p, 0xff15bfc8, 0xff07848c);
                member(c, p); wordmark(c, p, "会员中心", 169, 44, 0xffffffff, true); break;
            case 4: {
                background(c, p, 0xff78d3f4, 0xff1687d9);
                Path shape = oval(87, 101, 267, 281);
                Path cloud = new Path(); cloud.moveTo(171, 412); cloud.cubicTo(117, 414, 76, 374, 76, 321);
                cloud.cubicTo(76, 268, 120, 219, 170, 221); cloud.cubicTo(182, 221, 192, 225, 204, 230);
                cloud.cubicTo(228, 165, 280, 148, 332, 164); cloud.cubicTo(406, 169, 449, 229, 449, 290);
                cloud.cubicTo(449, 356, 409, 412, 332, 412); cloud.close(); shape.addPath(cloud);
                object(context, c, p, pkg, shape, new RectF(76, 101, 449, 414), 101, 108, 139, 120);
                wordmark(c, p, "天气", 191, 50, 0xffffffff, true); break;
            }
            case 5: {
                background(c, p, 0xff136a4f, 0xff124a3d);
                Path shield = new Path(); shield.moveTo(256, 71); shield.cubicTo(270, 73, 376, 112, 391, 123);
                shield.cubicTo(404, 132, 407, 145, 407, 162); shield.lineTo(407, 305);
                shield.cubicTo(407, 364, 311, 415, 256, 439); shield.cubicTo(201, 415, 104, 364, 104, 305);
                shield.lineTo(104, 162); shield.cubicTo(104, 142, 109, 128, 123, 119); shield.lineTo(244, 73); shield.close();
                object(context, c, p, pkg, shield, new RectF(104, 71, 407, 439), 103, 108, 112, 134);
                wordmark(c, p, "小聚助手", 167, 44, 0xffeefcf4, true); break;
            }
            case 6: {
                background(c, p, 0xffe9ddf3, 0xffcbd6ee);
                Path photos = rounded(69, 150, 445, 410, 43);
                photos.addRoundRect(new RectF(102, 122, 415, 143), 13, 13, Path.Direction.CW);
                photos.addRoundRect(new RectF(121, 92, 393, 111), 12, 12, Path.Direction.CW);
                object(context, c, p, pkg, photos, new RectF(69, 92, 445, 410), 101, 108, 136, 119);
                wordmark(c, p, "屏保画册", 184, 42, 0xff5e508c, true); break;
            }
            case 7:
                background(c, p, 0xff1ebec4, 0xff008f9b);
                wechat(c, p); wordmark(c, p, "微信电视", 184, 42, 0xfff4ffff, true); break;
            case 8: {
                background(c, p, 0xff697cf4, 0xff4844bf);
                Path calendar = rounded(79, 99, 433, 411, 42);
                calendar.addRoundRect(new RectF(160, 88, 177, 133), 8, 8, Path.Direction.CW);
                calendar.addRoundRect(new RectF(334, 88, 351, 133), 8, 8, Path.Direction.CW);
                object(context, c, p, pkg, calendar, new RectF(79, 88, 433, 411), 103, 108, 124, 122);
                wordmark(c, p, "日历", 191, 50, 0xffffffff, true); break;
            }
            case 9: {
                background(c, p, 0xffece3fa, 0xffd3d8ef);
                Path book = rounded(90, 98, 424, 414, 35);
                book.addRoundRect(new RectF(398, 145, 435, 220), 16, 16, Path.Direction.CW);
                book.addRoundRect(new RectF(397, 218, 431, 295), 15, 15, Path.Direction.CW);
                book.addRoundRect(new RectF(397, 289, 429, 367), 14, 14, Path.Direction.CW);
                object(context, c, p, pkg, book, new RectF(90, 98, 435, 414), 101, 108, 124, 126);
                wordmark(c, p, "智绘童话", 174, 44, 0xff694199, true); break;
            }
            case 10: homeLife(c, p); break;
            case 11: tutorial(c, p); break;
            case 12:
                background(c, p, 0xff293a9a, 0xff201054);
                object(context, c, p, pkg, oval(42, 35, 477, 478), new RectF(42, 35, 477, 478), 102, 108, 137, 137);
                wordmark(c, p, "语音专区", 184, 42, 0xfff4efff, true); break;
            case 13:
                background(c, p, 0xfff3f7f7, 0xffdae9e9);
                object(context, c, p, pkg, rounded(100, 71, 413, 443, 152), new RectF(100, 71, 413, 443), 102, 108, 112, 135);
                wordmark(c, p, "魔镜", 187, 50, 0xff427d80, true); break;
            case 14: settings(context, c, p, pkg); break;
            default: break;
        }
    }

    private static Path rounded(float l, float t, float r, float b, float radius) {
        Path path = new Path(); path.addRoundRect(new RectF(l, t, r, b), radius, radius, Path.Direction.CW); return path;
    }
    private static Path oval(float l, float t, float r, float b) {
        Path path = new Path(); path.addOval(new RectF(l, t, r, b), Path.Direction.CW); return path;
    }

    /** Draw only the original material object, preserving its colors and fine detail. */
    private static void object(Context context, Canvas c, Paint p, String pkg, Path silhouette,
            RectF sourceBounds, float cx, float cy, float maxW, float maxH) {
        Bitmap source = originalIcon(context, pkg, false);
        if (source == null) return;
        float scale = Math.min(maxW / sourceBounds.width(), maxH / sourceBounds.height());
        c.save(); c.translate(cx - sourceBounds.centerX() * scale, cy - sourceBounds.centerY() * scale);
        c.scale(scale, scale); c.clipPath(silhouette);
        c.drawBitmap(source, null, new RectF(0, 0, 512, 512), p); c.restore(); source.recycle();
    }

    private static Bitmap originalIcon(Context c, String pkg, boolean foreground) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            Drawable icon = ai.icon == 0 ? c.getPackageManager().getApplicationIcon(ai)
                    : c.getPackageManager().getResourcesForApplication(ai).getDrawableForDensity(ai.icon, DisplayMetrics.DENSITY_XXXHIGH, null);
            if (foreground && icon instanceof AdaptiveIconDrawable) icon = ((AdaptiveIconDrawable) icon).getForeground();
            Bitmap b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, 512, 512); icon.draw(new Canvas(b)); return b;
        } catch (Exception e) { AppLog.w("App artwork unavailable: " + pkg); return null; }
    }

    private static void stacked(Canvas c, Paint p, String first, String second, float x, float size, int color) {
        p.setTypeface(Ui.displaySemibold != null ? Ui.displaySemibold : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.setColor(color); p.setTextSize(size);
        float line = size * 1.07f, baseline = 108 - (p.ascent() + p.descent()) / 2;
        c.drawText(first, x, baseline - line / 2, p); c.drawText(second, x, baseline + line / 2, p);
    }

    private static void member(Canvas c, Paint p) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(10); p.setColor(0x2500434b);
        c.drawCircle(102, 86, 23, p);
        RectF body = new RectF(58, 115, 146, 157); c.drawRoundRect(body, 20, 20, p);
        p.setColor(0xfff0ffff); c.drawCircle(100, 83, 23, p);
        body.offset(-2, -3); c.drawRoundRect(body, 20, 20, p); p.setStyle(Paint.Style.FILL);
    }

    private static void wechat(Canvas c, Paint p) {
        p.setColor(0xfff0ffff); c.drawRoundRect(new RectF(42, 65, 148, 138), 5, 5, p);
        c.drawRoundRect(new RectF(43, 145, 144, 149), 2, 2, p);
        p.setColor(0xff09a4ae); p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); p.setTextSize(31);
        c.drawText("TV", 71, 113, p);
        p.setColor(0xffe9ffff); c.drawRoundRect(new RectF(126, 97, 169, 158), 6, 6, p);
        p.setColor(0xff09a4ae); c.drawRoundRect(new RectF(130, 104, 165, 145), 2, 2, p);
        p.setColor(0xffe9ffff); c.drawCircle(147.5f, 152, 2.5f, p);
    }

    private static void androidInfo(Canvas c, Paint p) {
        p.setColor(0xff78a947); p.setStrokeWidth(3.2f); p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(78, 59, 71, 47, p); c.drawLine(122, 59, 129, 47, p);
        c.drawArc(new RectF(66, 56, 134, 112), 180, 180, true, p);
        c.drawRoundRect(new RectF(66, 88, 134, 145), 6, 6, p);
        c.drawRoundRect(new RectF(51, 90, 62, 136), 5, 5, p); c.drawRoundRect(new RectF(138, 90, 149, 136), 5, 5, p);
        c.drawRoundRect(new RectF(77, 135, 89, 162), 5, 5, p); c.drawRoundRect(new RectF(111, 135, 123, 162), 5, 5, p);
        p.setColor(0xfff7fdeb); c.drawCircle(83, 72, 3, p); c.drawCircle(117, 72, 3, p);
        Path cube = new Path(); cube.moveTo(100, 98); cube.lineTo(119, 109); cube.lineTo(100, 120); cube.lineTo(81, 109); cube.close();
        c.drawPath(cube, p); p.setColor(0xffe3f0ce); cube.reset(); cube.moveTo(81, 112); cube.lineTo(98, 122); cube.lineTo(98, 141); cube.lineTo(81, 131); cube.close(); c.drawPath(cube, p);
        p.setColor(0xffd2e5b5); cube.reset(); cube.moveTo(102, 122); cube.lineTo(119, 112); cube.lineTo(119, 131); cube.lineTo(102, 141); cube.close(); c.drawPath(cube, p);
    }

    private static void homeLife(Canvas c, Paint p) {
        background(c, p, 0xff13bdc5, 0xff038d99);
        Path wave = new Path(); wave.moveTo(0, 177); wave.cubicTo(80, 152, 155, 209, 247, 182);
        wave.cubicTo(303, 166, 345, 169, 384, 181); wave.lineTo(384, 216); wave.lineTo(0, 216); wave.close();
        p.setColor(0x25ffffff); c.drawPath(wave, p);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); p.setTextSize(72); p.setColor(0xffffffff);
        String name = "爱家"; c.drawText(name, (384 - p.measureText(name)) / 2, 128, p);
        p.setTextSize(23); p.setTypeface(Ui.displaySemibold != null ? Ui.displaySemibold : Typeface.DEFAULT_BOLD);
        c.drawText("TV", (384 - p.measureText("TV")) / 2, 165, p);
    }

    private static void tutorial(Canvas c, Paint p) {
        background(c, p, 0xff568df2, 0xff5244bf);
        int[] colors = {0xff6da9ff, 0xffa7caff, 0xffd1d8ff};
        for (int i = 0; i < 3; i++) {
            Path page = new Path(); float y = 65 + i * 15;
            page.moveTo(44, y + 16); page.lineTo(106, y - 20); page.quadTo(123, y - 30, 134, y - 8);
            page.lineTo(157, y + 66); page.quadTo(164, y + 84, 145, y + 84); page.lineTo(55, y + 84);
            page.quadTo(41, y + 84, 42, y + 70); page.close(); p.setColor(colors[i]); c.drawPath(page, p);
        }
        wordmark(c, p, "玩机宝典", 177, 44, 0xffffffff, true);
    }

    private static void settings(Context context, Canvas c, Paint p, String pkg) {
        background(c, p, 0xff556776, 0xff293e50);
        Path gear = new Path();
        for (int i = 0; i < 256; i++) {
            double angle = Math.PI * 2 * i / 256 - Math.PI / 2;
            float radius = 189 - 16 * (float)Math.cos(angle * 16);
            float x = 256 + (float)Math.cos(angle) * radius, y = 256 + (float)Math.sin(angle) * radius;
            if (i == 0) gear.moveTo(x, y); else gear.lineTo(x, y);
        }
        gear.close(); object(context, c, p, pkg, gear, new RectF(51, 51, 461, 461), 103, 108, 129, 129);
        wordmark(c, p, "系统设置", 184, 42, 0xfff3f8ff, true);
    }

    private static void freeMyTv(Canvas c, Paint p) {
        background(c, p, 0xfff8979d, 0xffa183df);
        p.setColor(0xfffff8ff); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(6);
        c.drawRoundRect(new RectF(43, 63, 153, 139), 10, 10, p); p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(new RectF(77, 151, 119, 156), 2.5f, 2.5f, p);
        star(c, p, 97, 100, 20); star(c, p, 133, 79, 7);
        stacked(c, p, "Free My", "TV", 182, 40, 0xffffffff);
    }

    private static void star(Canvas c, Paint p, float x, float y, float radius) {
        Path path = new Path(); path.moveTo(x, y - radius); path.lineTo(x + radius * .25f, y - radius * .25f);
        path.lineTo(x + radius, y); path.lineTo(x + radius * .25f, y + radius * .25f);
        path.lineTo(x, y + radius); path.lineTo(x - radius * .25f, y + radius * .25f);
        path.lineTo(x - radius, y); path.lineTo(x - radius * .25f, y - radius * .25f); path.close(); c.drawPath(path, p);
    }

    private static void background(Canvas c, Paint p, int top, int bottom) {
        p.setShader(new LinearGradient(0, 0, 384, 216, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 384, 216, p); p.setShader(null);
    }
    private static void wordmark(Canvas c, Paint p, String title, float x, float size, int color, boolean nativeSans) {
        p.setTypeface(nativeSans ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                : Ui.displaySemibold != null ? Ui.displaySemibold : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.setTextSize(size); p.setColor(color);
        c.drawText(title, x, 108 - (p.ascent() + p.descent()) / 2, p);
    }

    private static void media(Canvas c, Paint p) {
        background(c, p, 0xff8153e8, 0xff4b269f);
        // The original purple folder and play motif, without another square container around it.
        c.save(); c.translate(-13, 0); c.scale(.90f, 1);
        Path back = new Path();
        back.moveTo(57, 67); back.quadTo(57, 59, 65, 59); back.lineTo(88, 59);
        back.quadTo(94, 59, 98, 67); back.lineTo(153, 67); back.quadTo(165, 67, 165, 79);
        back.lineTo(165, 143); back.quadTo(165, 154, 154, 154); back.lineTo(66, 154);
        back.quadTo(55, 154, 55, 143); back.close();
        p.setShader(new LinearGradient(55, 59, 155, 154, 0xffd2b9ff, 0xffad87f4, Shader.TileMode.CLAMP));
        c.drawPath(back, p); p.setShader(null);
        p.setColor(0xfff5edff); c.drawRoundRect(new RectF(66, 74, 151, 138), 7, 7, p);
        p.setShader(new LinearGradient(60, 84, 160, 157, 0xfff2eaff, 0xffcdb7f4, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(55, 84, 167, 158), 11, 11, p); p.setShader(null);
        Path play = new Path(); play.moveTo(101, 104); play.lineTo(101, 139); play.quadTo(101, 143, 105, 140);
        play.lineTo(128, 124); play.quadTo(132, 121, 128, 118); play.lineTo(105, 102); play.quadTo(101, 99, 101, 104);
        p.setShader(new LinearGradient(101, 102, 130, 140, 0xffa15bea, 0xff6b30c9, Shader.TileMode.CLAMP));
        c.drawPath(play, p); p.setShader(null);
        c.restore();
        wordmark(c, p, "媒体中心", 162, CHINESE_WORDMARK_SIZE, 0xfffcf9ff, true);
    }

    /** Read the installed app's own foreground, retaining the original silhouette, tail, eyes and mouth. */
    private static Bitmap originalClash(Context c) {
        try {
            ApplicationInfo app = c.getPackageManager().getApplicationInfo("com.github.kr328.clash", 0);
            Drawable icon = app.icon == 0 ? c.getPackageManager().getApplicationIcon(app)
                    : c.getPackageManager().getResourcesForApplication(app).getDrawableForDensity(app.icon, DisplayMetrics.DENSITY_XXXHIGH, null);
            Drawable mark = icon instanceof AdaptiveIconDrawable ? ((AdaptiveIconDrawable) icon).getForeground() : icon;
            if (mark == null) return null;
            Bitmap source = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            mark.setBounds(0, 0, 512, 512); mark.draw(new Canvas(source));
            int[] pixels = new int[512 * 512]; source.getPixels(pixels, 0, 512, 0, 0, 512, 512);
            int left = 512, top = 512, right = -1, bottom = -1;
            for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) if ((pixels[y * 512 + x] >>> 24) > 10) {
                left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y);
            }
            if (right < left || bottom < top) { source.recycle(); return null; }
            Bitmap cropped = Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1);
            if (cropped != source) source.recycle();
            return cropped;
        } catch (Exception e) { AppLog.w("Original Clash artwork unavailable"); return null; }
    }

    private static void clash(Canvas c, Paint p, Bitmap original) {
        background(c, p, 0xfff9fbff, 0xffe6eef9);
        float fit = Math.min(104f / original.getWidth(), 124f / original.getHeight());
        float w = original.getWidth() * fit, h = original.getHeight() * fit;
        p.setColor(0xffffffff);
        c.drawBitmap(original, null, new RectF(105 - w / 2, 108 - h / 2, 105 + w / 2, 108 + h / 2), p);
        wordmark(c, p, "Clash", 173, 50, 0xff255ea7, false);
    }

    private static void stock(Canvas c, Paint p) {
        c.drawColor(0xffffffff);
        c.save(); c.translate(40, 47); c.scale(.94f, .94f);
        Path mark = new Path();
        mark.moveTo(35, 14); mark.cubicTo(44, 14, 99, 45, 109, 53);
        mark.cubicTo(122, 62, 122, 69, 110, 77); mark.lineTo(42, 120);
        mark.cubicTo(31, 127, 24, 123, 23, 111); mark.cubicTo(19, 89, 19, 48, 24, 29);
        mark.cubicTo(25, 18, 28, 14, 35, 14); mark.close();
        p.setShader(new LinearGradient(25, 15, 108, 112, 0xfff43e97, 0xfff20542, Shader.TileMode.CLAMP));
        c.drawPath(mark, p); p.setShader(null);
        c.save(); c.rotate(-10, 64, 58); p.setColor(0xffffffff);
        c.drawOval(new RectF(40, 38, 88, 79), p); c.restore();
        p.setColor(0xfff51c65); c.drawCircle(69, 54, 9, p);
        c.restore();
        wordmark(c, p, "聚好看", 184, CHINESE_WORDMARK_SIZE, 0xff24252a, true);
    }
}
