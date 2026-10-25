package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.SparseArray;

/** Original geometric index marks, with one stroke weight and frame, tinted by the home screen's theme. */
final class Glyphs {
    private Glyphs() {}
    private static final SparseArray<Bitmap> BITMAPS = new SparseArray<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    static void coin(Context context, Canvas c, String index, float cx, float cy, float cap, int color) {
        Bitmap b = bitmap(index, frame(cap));
        PAINT.setColor(color);
        c.drawBitmap(b, Math.round(cx - b.getWidth() / 2f), Math.round(cy - b.getHeight() / 2f), PAINT);
    }

    static float width(Context context, String index, float cap) { return frame(cap); }
    private static int frame(float cap) { return Math.max(12, Math.round(cap * 1.6f)); }

    private static Bitmap bitmap(String index, int frame) {
        int slot = index.equals("IXIC") ? 0 : index.equals("HSTECH") ? 1 : 2;
        int key = slot << 16 | frame;
        synchronized (BITMAPS) {
            Bitmap b = BITMAPS.get(key);
            if (b != null) return b;
            Bitmap full = Bitmap.createBitmap(frame, frame, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(full);
            c.scale(frame / 32f, frame / 32f);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.6f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            if (slot == 0) {
                // A rising N: distinct from an exchange's official trademark.
                Path n = new Path();
                n.moveTo(7, 24); n.lineTo(7, 9); n.lineTo(24, 24); n.lineTo(24, 8);
                c.drawPath(n, p);
                c.drawLine(20, 8, 24, 8, p);
                c.drawLine(24, 8, 24, 12, p);
            } else if (slot == 1) {
                // A technology circuit in an H arrangement.
                c.drawLine(8, 8, 8, 24, p); c.drawLine(24, 8, 24, 24, p);
                c.drawLine(8, 16, 24, 16, p);
                p.setStyle(Paint.Style.FILL);
                for (float x : new float[] {8, 24}) for (float y : new float[] {8, 24}) c.drawCircle(x, y, 2.4f, p);
                c.drawCircle(16, 16, 2.4f, p);
            } else {
                // A rounded mainland A, matching the other index monograms.
                Path a = new Path();
                a.moveTo(6, 25); a.lineTo(16, 7); a.lineTo(26, 25);
                c.drawPath(a, p);
                c.drawLine(9.9f, 18, 22.1f, 18, p);
            }
            b = full.extractAlpha();
            full.recycle();
            BITMAPS.put(key, b);
            return b;
        }
    }
}
