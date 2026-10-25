package io.freemytv;

import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** One-shot, short-lived marker shared only by our home and key-service processes. No network or credentials. */
final class HisenseHomeEntry {
    private static AtomicFile file(Context c) { return new AtomicFile(new File(c.getFilesDir(), "stock-home-entry.txt")); }

    static boolean allow(Context c) {
        AtomicFile f = file(c); FileOutputStream out = null;
        try {
            out = f.startWrite();
            out.write(Long.toString(SystemClock.elapsedRealtime()).getBytes(StandardCharsets.US_ASCII));
            f.finishWrite(out);
            return true;
        } catch (Exception e) { if (out != null) f.failWrite(out); return false; }
    }

    static boolean pending(Context c) { return read(c, false); }
    static boolean consume(Context c) { return read(c, true); }
    static void cancel(Context c) { file(c).delete(); }

    private static boolean read(Context c, boolean consume) {
        AtomicFile f = file(c);
        try {
            byte[] bytes = f.readFully();
            if (consume) f.delete();
            if (bytes.length > 24) return false;
            long age = SystemClock.elapsedRealtime() - Long.parseLong(new String(bytes, StandardCharsets.US_ASCII));
            return age >= 0 && age <= 10000;
        } catch (Exception e) { return false; }
    }
}
