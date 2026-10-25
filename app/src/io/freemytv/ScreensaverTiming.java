package io.freemytv;

import android.content.Context;
import java.io.IOException;

/** Home-only inactivity timer. Some TV firmware ignores Android's screen-off/dream timeout. */
final class ScreensaverTiming {
    static final long[] OPTIONS = {60000, 120000, 300000, 600000, 900000, 1800000};
    static long read(Context context) {
        long value = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getLong("screensaverDelayMs", OPTIONS[0]);
        for (long choice : OPTIONS) if (value == choice) return value;
        return OPTIONS[0];
    }
    static long next(long current, int direction) {
        for (int i = 0; i < OPTIONS.length; i++) if (OPTIONS[i] == current)
            return OPTIONS[(i + direction + OPTIONS.length) % OPTIONS.length];
        return direction > 0 ? OPTIONS[0] : OPTIONS[OPTIONS.length - 1];
    }
    static void set(Context context, long millis) throws IOException {
        boolean allowed = false;
        for (long choice : OPTIONS) if (choice == millis) allowed = true;
        if (!allowed) throw new IOException("Unsupported screen saver time");
        if (!context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putLong("screensaverDelayMs", millis).commit()) throw new IOException(Ui.tr("Connection failed"));
        Prefs.version++;
    }
}
