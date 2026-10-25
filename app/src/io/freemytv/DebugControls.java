package io.freemytv;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Explicit in-home switches only. Observes the same two fixed settings; never writes the wireless-debugging setting. */
final class DebugControls {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static boolean observing;
    private static volatile boolean on, ready;
    private static volatile String state = "idle", before = "unknown";

    static boolean on() { return on; }
    static boolean busy() { return BUSY.get(); }
    static String symbol() { return on ? "debug_on" : "debug_off"; }
    static String label() { return Ui.trf("Debugging: {state}", "state", on ? Ui.tr("On") : Ui.tr("Off")); }
    private static DebugPlan.Store store(Context c) {
        return new DebugPlan.Store() {
            @Override public String read(String key) { return Settings.Global.getString(c.getContentResolver(), key); }
            @Override public void write(String key, String value) throws Exception {
                if (!Settings.Global.putString(c.getContentResolver(), key, value)) throw new Exception();
            }
        };
    }
    static void subscribe(Context context, Runnable listener) {
        Context c = context.getApplicationContext(); LISTENERS.addIfAbsent(listener);
        if (!observing) {
            observing = true;
            ContentObserver observer = new ContentObserver(MAIN) {
                @Override public void onChange(boolean self) { refresh(c); }
            };
            c.getContentResolver().registerContentObserver(Settings.Global.getUriFor("development_settings_enabled"), false, observer);
            c.getContentResolver().registerContentObserver(Settings.Global.getUriFor("adb_enabled"), false, observer);
        }
        refresh(c);
    }
    static void unsubscribe(Runnable listener) { LISTENERS.remove(listener); }
    private static void changed() { for (Runnable listener : LISTENERS) listener.run(); }
    private static void refresh(Context c) {
        IO.execute(() -> {
            try { on = DebugPlan.enabled(store(c)); } catch (Exception ignored) { }
            MAIN.post(DebugControls::changed);
        });
    }
    static void enable(Context c, Consumer<String> result) { set(c, true, result, success -> { }); }

    static void set(Context context, boolean enabled, Consumer<String> result, Consumer<Boolean> completed) {
        Context app = context.getApplicationContext();
        if (app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            state = "permission-needed"; result.accept(Ui.tr("Permission needed")); completed.accept(false); return;
        }
        if (!BUSY.compareAndSet(false, true)) { result.accept(Ui.tr("Connecting…")); completed.accept(false); return; }
        state = enabled ? "enabling" : "disabling";
        result.accept(Ui.tr("Connecting…")); changed();
        if (enabled) perform(app, true, result, completed);
        else StockHomeGate.beforeDebugOff(app, failure -> {
            if (failure == null) perform(app, false, result, completed);
            else {
                state = "disable-cancelled"; BUSY.set(false); changed();
                result.accept(failure); completed.accept(false);
            }
        });
    }

    private static boolean connectionOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5555), 200); return true;
        } catch (Exception ignored) { return false; }
    }
    private static void perform(Context app, boolean enabled, Consumer<String> result, Consumer<Boolean> completed) {
        IO.execute(() -> {
            String message; boolean success = false;
            try {
                DebugPlan.Store settings = store(app);
                before = String.valueOf(settings.read("development_settings_enabled")) + "/" + String.valueOf(settings.read("adb_enabled"));
                if (enabled) DebugPlan.enable(settings); else DebugPlan.disable(settings);
                for (int i = 0; i < 8; i++) {
                    ready = connectionOpen(); if (ready == enabled) break; Thread.sleep(250);
                }
                on = DebugPlan.enabled(settings);
                if (enabled != on || (!enabled && (!"0".equals(settings.read("development_settings_enabled"))
                        || !"0".equals(settings.read("adb_enabled"))))) throw new Exception();
                success = ready == enabled;
                state = enabled ? ready ? "enabled-ready" : "enabled-not-ready" : ready ? "disabled-connection-open" : "disabled";
                message = enabled ? ready ? Ui.tr("Debugging enabled") : Ui.tr("Switches are on; the debugging connection is not ready.")
                        : ready ? Ui.tr("Switches are off; the debugging connection is still closing.") : Ui.tr("Debugging disabled");
            } catch (DebugPlan.Failure e) {
                state = e.restored ? "failed-restored" : "failed-check-settings";
                message = Ui.tr("Couldn't change debugging. Check system settings.");
            } catch (Exception e) {
                state = "failed-check-settings"; message = Ui.tr("Couldn't change debugging. Check system settings.");
            } finally {
                try { on = DebugPlan.enabled(store(app)); } catch (Exception ignored) { }
                BUSY.set(false);
            }
            String finished = message; boolean ok = success;
            MAIN.post(() -> {
                changed(); result.accept(finished); completed.accept(ok);
                if (enabled && ready) StockHomeGate.retryOnHome(app);
            });
        });
    }

    static String status(Context context) {
        return "permission=" + (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
                + " state=" + state + " on=" + on + " before=" + before + " localConnectionReady=" + ready;
    }
}
