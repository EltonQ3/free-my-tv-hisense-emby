package io.freemytv;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Release the vendor HDMI plane when this home owns the screen; leave real input viewing alone. */
final class HomeSource {
    private static final String VENDOR = "com.hisense.tv.tiletransition";
    private static final String ANDROID_SOURCE = "com.hisense.app.set_android_source";
    private static final String COMPLETE = "com.jamdeo.tv.systemui.SILO_INPUT_SOURCE_COMPLETE";
    private static final int ANDROID_ID = 2562;
    private static final long[] RETRY_MS = {1000, 2000, 4000, 8000, 15000, 30000, 30000, 30000};
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final boolean supported = HomeMode.keepHisenseApps();
    private boolean resumed, registered;
    private int attempts, sourceId = -1;
    private long requestedAt, confirmedAt, lastRequest, yieldUntil;
    private String state = "idle";

    HomeSource(Activity activity) { this.activity = activity; }

    private final Runnable repair = this::request;
    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                if (ownsScreen()) { attempts = 0; schedule(750); }
                return;
            }
            if (!COMPLETE.equals(intent.getAction())) return;
            sourceId = intent.getIntExtra("jamdeo_current_silo_input_id", -1);
            if (sourceId == ANDROID_ID) {
                confirmedAt = System.currentTimeMillis(); state = "confirmed";
                if (attempts > 0) handler.removeCallbacks(repair);
            } else if (sourceId == -1 && "waiting".equals(state) && attempts > 0
                    && SystemClock.elapsedRealtime() - lastRequest < 2000) {
                // At boot the vendor has no current-app object, so its successful completion omits the source extra.
                confirmedAt = System.currentTimeMillis(); state = "acknowledged";
                handler.removeCallbacks(repair);
            } else if (sourceId >= 0 && ownsScreen()) {
                // A late boot/CEC handoff can occur after onResume. Ignore it when the user is viewing an input.
                schedule(Math.max(250, 1000 - (SystemClock.elapsedRealtime() - lastRequest)));
            }
            record();
        }
    };

    void resume() {
        if (!supported) return;
        resumed = true; attempts = 0; yieldUntil = 0;
        if (!registered) {
            IntentFilter filter = new IntentFilter(COMPLETE);
            filter.addAction(Intent.ACTION_BOOT_COMPLETED);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(changes, filter, Context.RECEIVER_EXPORTED);
            else activity.registerReceiver(changes, filter);
            registered = true;
        }
        schedule(150);
    }

    void focus(boolean focused) {
        if (!supported) return;
        handler.removeCallbacks(repair);
        if (focused && resumed) { attempts = 0; schedule(150); }
    }

    void homeIntent() { if (supported && resumed) schedule(150); }

    void yieldInputs() {
        // The source picker may dismiss just before LiveTV becomes resumed. Do not race that short handoff.
        yieldUntil = SystemClock.elapsedRealtime() + 1500;
        handler.removeCallbacks(repair);
    }

    private boolean ownsScreen() {
        return resumed && activity.hasWindowFocus() && !activity.isFinishing();
    }

    private void schedule(long delay) {
        handler.removeCallbacks(repair);
        handler.postDelayed(repair, Math.max(delay, yieldUntil - SystemClock.elapsedRealtime()));
    }

    private void request() {
        if (!ownsScreen()) return;
        if (SystemClock.elapsedRealtime() < yieldUntil) { schedule(150); return; }
        if (attempts >= RETRY_MS.length) { state = "unconfirmed"; record(); return; }
        attempts++; requestedAt = System.currentTimeMillis(); lastRequest = SystemClock.elapsedRealtime();
        state = "waiting";
        try {
            // Observed vendor receiver selects Android's source and emits its normal completion notification.
            activity.sendBroadcast(new Intent(ANDROID_SOURCE).setPackage(VENDOR));
            schedule(RETRY_MS[attempts - 1]);    // the vendor source service can start well after the home screen
        } catch (RuntimeException e) { state = "request_denied"; }
        record();
    }

    void pause() {
        resumed = false; handler.removeCallbacks(repair);
        if (registered) { activity.unregisterReceiver(changes); registered = false; }
    }

    private void record() {
        try {
            final byte[] value = new JSONObject().put("state", state).put("sourceId", sourceId)
                    .put("attempts", attempts).put("requestedAt", requestedAt).put("confirmedAt", confirmedAt)
                    .toString().getBytes(StandardCharsets.UTF_8);
            final Context context = activity.getApplicationContext();
            IO.execute(() -> {
                AtomicFile file = file(context); FileOutputStream out = null;
                try { out = file.startWrite(); out.write(value); file.finishWrite(out); }
                catch (Exception e) { if (out != null) file.failWrite(out); }
            });
        } catch (Exception ignored) { }
    }

    private static AtomicFile file(Context c) { return new AtomicFile(new File(c.getFilesDir(), "home-source-status.json")); }
    static JSONObject status(Context c) {
        try { return new JSONObject(new String(file(c).readFully(), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new JSONObject(); }
    }
}
