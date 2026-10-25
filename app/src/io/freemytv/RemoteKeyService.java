package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Remote app buttons and UXS Home routing from window identity only. Never reads window content. */
public class RemoteKeyService extends AccessibilityService {
    private final HisenseHomePolicy home = new HisenseHomePolicy();
    private final Set<String> keyboards = new HashSet<>();
    private int stockEvents, redirects, visits, homeSignals, earlyLaunches, corrections;
    private long lastHomeAt = -1, lastStockAt = -1, stockDeliveryLag;
    private boolean registered;
    private String lastApp = "", lastClass = "", result = "idle";
    private final BroadcastReceiver homeKey = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                home.reset();
                return;
            }
            if (!Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())
                    || !"homekey".equals(intent.getStringExtra("reason")) || !freeMode()) return;
            homeSignals++;
            HisenseHomeEntry.cancel(context);
            lastHomeAt = SystemClock.elapsedRealtime();
            if (home.home(lastHomeAt)) {
                earlyLaunches++; openHome();
            }
        }
    };
    @Override protected void onServiceConnected() {
        if (!HomeMode.keepHisenseApps()) return;
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) for (InputMethodInfo method : manager.getInputMethodList()) keyboards.add(method.getPackageName());
        if (!registered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(homeKey, filter, Context.RECEIVER_EXPORTED);
            else registerReceiver(homeKey, filter);
            registered = true;
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!HomeMode.keepHisenseApps() || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        String cls = event.getClassName() == null ? "" : event.getClassName().toString();
        if (pkg.isEmpty() || pkg.equals("android") || pkg.equals("com.android.systemui") || keyboards.contains(pkg)) return;
        if (!freeMode()) { home.reset(); return; }
        lastApp = pkg; lastClass = cls;
        if (!pkg.equals("com.jamdeo.tv.vod")) return;
        stockEvents++;
        if (!cls.equals("com.ju.main.launcher.LauncherActivity")
                && !cls.equals("com.ju.homepage.launcher.LauncherActivity")) return;
        lastStockAt = SystemClock.elapsedRealtime();
        stockDeliveryLag = Math.max(0, SystemClock.uptimeMillis() - event.getEventTime());
        boolean deliberate = HisenseHomeEntry.consume(this);
        if (deliberate) visits++;
        if (!home.stock(deliberate, lastStockAt)) { result = "stock-stays"; return; }
        corrections++;
        openHome();
    }

    private boolean freeMode() {
        JSONObject mode = HomeMode.state(this);
        return HomeMode.FREE.equals(mode.optString("mode")) && !mode.optBoolean("homeReverted");
    }

    private void openHome() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setComponent(new ComponentName(this, HomeActivity.class))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
            redirects++; result = "opened-free";
        } catch (RuntimeException e) { result = "unavailable"; }
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        if (registered) { unregisterReceiver(homeKey); }
        super.onDestroy();
    }

    @Override public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("HisenseDualHome: stockEvents=" + stockEvents + " redirects=" + redirects
                + " deliberateVisits=" + visits + " homeSignals=" + homeSignals + " result=" + result);
        writer.println("HomeTiming: direct=" + earlyLaunches + " corrections=" + corrections
                + " signalAt=" + lastHomeAt + " stockAt=" + lastStockAt + " stockEventLagMs=" + stockDeliveryLag);
        writer.println("LastWindowIdentity: " + lastApp + "/" + lastClass);
        writer.println("HomeTransition: disabled");
    }
}
