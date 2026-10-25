package io.freemytv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Opt-in, fixed-package gate. No daemon, exported receiver, automatic debugging switch or boot launch. */
final class StockHomeGate {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile JSONObject cached;
    private static volatile boolean visible, launching;
    private static volatile long generation;
    private static boolean changing;

    private static AtomicFile file(Context c) { return new AtomicFile(new File(c.getFilesDir(), "stock-home-gate.json")); }
    private static JSONObject read(Context c) {
        try { return new JSONObject(new String(file(c).readFully(), StandardCharsets.UTF_8)); }
        catch (Exception ignored) { return new JSONObject(); }
    }
    private static void save(Context c, JSONObject state) throws Exception {
        AtomicFile f = file(c); FileOutputStream out = null;
        try {
            out = f.startWrite(); out.write(state.toString().getBytes(StandardCharsets.UTF_8)); f.finishWrite(out);
            cached = new JSONObject(state.toString());
        } catch (Exception e) { if (out != null) f.failWrite(out); throw e; }
    }
    static void refresh(Context c) { cached = read(c); }
    static boolean enabled(Context c) {
        if (!HomeMode.keepHisenseApps()) return false;
        if (cached == null) refresh(c);
        return cached.optBoolean("onDemand");
    }
    static boolean manages(Context c) {
        JSONObject s = read(c);
        return s.optBoolean("onDemand") || s.optBoolean("owned");
    }
    static String status(Context c) {
        if (cached == null) refresh(c);
        return "onDemand=" + cached.optBoolean("onDemand") + " owned=" + cached.optBoolean("owned")
                + " phase=" + cached.optString("phase", "unrestricted") + " homeVisible=" + visible + " launching=" + launching;
    }
    private static RandomAccessFile mutex(Context c) throws Exception {
        return new RandomAccessFile(new File(c.getFilesDir(), "stock-home-gate.lock"), "rw");
    }
    private static boolean stockDisabled(Context c) {
        int state = c.getPackageManager().getApplicationEnabledSetting("com.jamdeo.tv.vod");
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }
    private static boolean free(Context c) {
        JSONObject s = HomeMode.state(c);
        return HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted") && s.optString("busy").isEmpty();
    }
    private static String error(Exception e) {
        String message = e.getMessage();
        return Ui.trf("Error: {error}.", "error", message == null ? Ui.tr("Connection failed") : message);
    }
    private static void notice(Context c, String text) {
        MAIN.post(() -> Toast.makeText(c, text, Toast.LENGTH_LONG).show());
    }
    private static Adb connect(Context c, boolean explicit) throws Exception {
        return Adb.connect(c, explicit ? 60_000 : 5000, explicit
                ? () -> notice(c, Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”).")) : null);
    }

    /** Called from the settings choice. The previous mode remains in place if opening the connection fails. */
    static void configure(Context context, boolean on, Consumer<String> result) {
        if (changing) { result.accept(Ui.tr("Connecting…")); return; }
        changing = true;
        Context c = context.getApplicationContext();
        result.accept(Ui.tr("Connecting…"));
        IO.execute(() -> {
            String message;
            try (RandomAccessFile lockFile = mutex(c); FileLock lock = lockFile.getChannel().lock(); Adb adb = connect(c, true)) {
                if (on) {
                    if (!free(c)) throw new Exception(Ui.tr("Connection failed"));
                    HomeMode.checkStockGate(c, adb);
                    JSONObject s = read(c);
                    StockHomePlan.Desktop desktop = HomeMode.stockDesktop(adb);
                    // Never adopt a package the person disabled outside this feature.
                    if (desktop.disabled() && !s.optBoolean("owned")) throw new Exception(Ui.tr("Connection failed"));
                    JSONObject before = new JSONObject(s.toString());
                    save(c, s.put("onDemand", true).put("owned", true).put("phase", "blocking"));
                    try {
                        HomeMode.recordStockGate(adb, true);
                        StockHomePlan.lock(desktop);
                        save(c, s.put("phase", "blocked"));
                    }
                    catch (Exception e) {
                        if (!desktop.disabled() && !before.optBoolean("owned")) {
                            try { HomeMode.recordStockGate(adb, false); } catch (Exception ignored) { }
                            save(c, before);
                        }
                        throw e;
                    }
                } else restoreLocked(c, adb);
                message = Ui.tr("Saved");
            } catch (Exception e) { message = error(e); }
            String finished = message;
            MAIN.post(() -> { changing = false; result.accept(finished); });
        });
    }

    /** The Original switch and crash watchdog use the same lock as the home process. */
    static void restoreOriginal(Context c, Adb adb) throws Exception {
        if (!HomeMode.keepHisenseApps()) return;
        try (RandomAccessFile lockFile = mutex(c); FileLock lock = lockFile.getChannel().lock()) {
            restoreLocked(c, adb);
        }
    }
    private static void restoreLocked(Context c, Adb adb) throws Exception {
        JSONObject s = read(c);
        if (s.optBoolean("owned") || s.optBoolean("onDemand")) {
            StockHomePlan.release(HomeMode.stockDesktop(adb));
            HomeMode.recordStockGate(adb, false);
        }
        save(c, s.put("onDemand", false).put("owned", false).put("phase", "unrestricted"));
    }

    /** Call only after two visible home frames; focus loss invalidates pending work before any disable. */
    static void homeVisible(Context context, boolean shown) {
        if (visible == shown) return;
        visible = shown; generation++;
        if (shown) retryOnHome(context);
    }
    static void retryOnHome(Context context) {
        if (!visible || launching || !enabled(context)) return;
        Context c = context.getApplicationContext(); long ticket = generation;
        MAIN.postDelayed(() -> {
            if (!visible || launching || ticket != generation) return;
            IO.execute(() -> {
                try (RandomAccessFile lockFile = mutex(c); FileLock lock = lockFile.getChannel().lock()) {
                    JSONObject s = read(c);
                    if (!s.optBoolean("onDemand") || !free(c) || !visible || launching || ticket != generation) return;
                    // Once verified blocked, everyday Home use needs neither ADB nor another debugging prompt.
                    if (s.optBoolean("owned") && "blocked".equals(s.optString("phase")) && stockDisabled(c)) return;
                    try (Adb adb = connect(c, false)) {
                        if (!visible || launching || ticket != generation || !free(c)) return;
                        HomeMode.checkStockGate(c, adb);
                        if (!visible || launching || ticket != generation || !free(c)) return;
                        HomeMode.recordStockGate(adb, true);
                        save(c, s.put("owned", true).put("phase", "blocking"));
                        StockHomePlan.lock(HomeMode.stockDesktop(adb));
                        save(c, s.put("phase", "blocked"));
                    }
                } catch (Exception e) { if (visible && !launching) notice(c, error(e)); }
            });
        }, 200);
    }

    /** Complete any outstanding relock before the user's explicit Off request closes our only privileged channel. */
    static void beforeDebugOff(Context context, Consumer<String> complete) {
        Context c = context.getApplicationContext();
        IO.execute(() -> {
            String failure = null;
            try (RandomAccessFile lockFile = mutex(c); FileLock lock = lockFile.getChannel().lock()) {
                JSONObject s = read(c);
                if (s.optBoolean("onDemand") && !(s.optBoolean("owned") && "blocked".equals(s.optString("phase")) && stockDisabled(c))) {
                    if (!free(c) || launching) throw new Exception(Ui.tr("Connection failed"));
                    try (Adb adb = connect(c, true)) {
                        HomeMode.checkStockGate(c, adb);
                        HomeMode.recordStockGate(adb, true);
                        save(c, s.put("owned", true).put("phase", "blocking"));
                        StockHomePlan.lock(HomeMode.stockDesktop(adb));
                        save(c, s.put("phase", "blocked"));
                    }
                }
            } catch (Exception e) { failure = error(e); }
            String result = failure; MAIN.post(() -> complete.accept(result));
        });
    }

    static void open(Activity activity) {
        if (launching || changing) { notice(activity, Ui.tr("Connecting…")); return; }
        launching = true;
        Context c = activity.getApplicationContext();
        notice(c, Ui.tr("Connecting…"));
        IO.execute(() -> {
            String failure = null;
            try (RandomAccessFile lockFile = mutex(c); FileLock lock = lockFile.getChannel().lock()) {
                JSONObject s = read(c);
                if (!s.optBoolean("onDemand") || !free(c)) throw new Exception(Ui.tr("Connection failed"));
                if (stockDisabled(c)) try (Adb adb = connect(c, true)) {
                    StockHomePlan.release(HomeMode.stockDesktop(adb));
                }
                save(c, s.put("phase", "available"));
            } catch (Exception e) { failure = error(e); }
            String finished = failure;
            MAIN.post(() -> {
                if (finished != null) {
                    launching = false; notice(c, finished); retryOnHome(c);
                    return;
                }
                launchWhenFocused(activity, c, SystemClock.uptimeMillis() + 2000);
            });
        });
    }

    private static void launchWhenFocused(Activity activity, Context c, long deadline) {
        if (!activity.isFinishing() && !activity.isDestroyed()) {
            if (!activity.hasWindowFocus() && SystemClock.uptimeMillis() < deadline) {
                MAIN.postDelayed(() -> launchWhenFocused(activity, c, deadline), 100);
                return;
            }
            if (activity.hasWindowFocus() && free(c)) {
                try {
                    if (!HisenseHomeEntry.allow(c)) throw new Exception();
                    activity.startActivity(new Intent(Intent.ACTION_MAIN)
                            .setComponent(ComponentName.unflattenFromString(HomeMode.HISENSE_HOME))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) { notice(c, Ui.trf("Couldn't open {app}.", "app", "聚好看")); }
            }
        }
        // A delayed pause or permission dialog must not relock the package while it is opening.
        MAIN.postDelayed(() -> { launching = false; retryOnHome(c); }, 1000);
    }
}
