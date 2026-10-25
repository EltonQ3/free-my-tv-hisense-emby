package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import org.json.JSONObject;
import java.net.URLEncoder;

/** One small avatar for the recommendation profile, fetched with header authentication on a worker. */
final class EmbyProfile {
    static final class Snapshot {
        final String name, initial;
        final Bitmap image;
        Snapshot(String name, Bitmap image) {
            this.name = name; this.image = image;
            initial = name.isEmpty() ? "" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT);
        }
    }
    static final Snapshot EMPTY = new Snapshot("", null);

    static Snapshot load(Context context) throws Exception {
        JSONObject config = EmbyRecs.readConfig(context);
        String id = config.getString("user_id");
        JSONObject user = new JSONObject(EmbyRecs.request(config, "/Users/" + id));
        return loadUser(config, user);
    }

    static Snapshot loadUser(JSONObject config, JSONObject user) throws Exception {
        return loadUser(config, user.getString("Id"), user.optString("Name", config.optString("user_name")).trim(),
                user.optString("PrimaryImageTag"));
    }

    static Snapshot loadUser(JSONObject config, String id, String name, String tag) throws Exception {
        if (!EmbyRecs.validId(id)) throw new java.io.IOException("Invalid profile");
        Bitmap avatar = null;
        if (!tag.isEmpty()) {
            try {
                byte[] bytes = EmbyRecs.fetchBytes(config, "/Users/" + id
                        + "/Images/Primary?maxWidth=128&maxHeight=128&quality=90&tag="
                        + URLEncoder.encode(tag, "UTF-8"), 1024 * 1024);
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth > 0 && options.outHeight > 0 && options.outWidth <= 4096 && options.outHeight <= 4096) {
                    options.inJustDecodeBounds = false;
                    while (Math.max(options.outWidth, options.outHeight) / Math.max(1, options.inSampleSize) > 256)
                        options.inSampleSize = options.inSampleSize == 0 ? 2 : options.inSampleSize * 2;
                    Bitmap original = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    if (original != null) {
                        avatar = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                        int size = Math.min(original.getWidth(), original.getHeight());
                        int x = (original.getWidth() - size) / 2, y = (original.getHeight() - size) / 2;
                        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                        new Canvas(avatar).drawBitmap(original, new Rect(x, y, x + size, y + size), new Rect(0, 0, 128, 128), paint);
                        avatar.setHasMipMap(true); original.recycle();
                    }
                }
            } catch (Exception ignored) { }    // A missing avatar must not hide the profile name or recommendations.
        }
        return new Snapshot(name, avatar);
    }
}
