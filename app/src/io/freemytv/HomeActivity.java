package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    private static final ExecutorService PROBE = Executors.newSingleThreadExecutor();
    private volatile String playbackProbe = "{}";
    private volatile String playerProbe = "{}";
    private HomeView home;
    private HomeSource source;
    private FrameLayout content;
    private DreamView saver;
    private boolean resumed, focused;
    private int wakeKey = -1;
    private long lastInteraction;
    private final Handler idle = new Handler(Looper.getMainLooper());
    private final Runnable startSaver = () -> {
        if (!resumed || !focused || home == null || saver != null || isFinishing()) return;
        long remaining = ScreensaverTiming.read(this) - (SystemClock.uptimeMillis() - lastInteraction);
        if (remaining > 0) { idle.postDelayed(this.startSaver, remaining); return; }
        // Reuse the actual dream renderer without requiring shell access or changing global TV power policy.
        home.pause();
        home.setVisibility(View.GONE);
        saver = new DreamView(this);
        content.addView(saver, new FrameLayout.LayoutParams(-1, -1));
        saver.start();
    };
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        source = new HomeSource(this);
        home = new HomeView(this);
        content = new FrameLayout(this);
        content.addView(home, new FrameLayout.LayoutParams(-1, -1));
        setContentView(content);
        getWindow().setBackgroundDrawable(null);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
        if (source != null) source.resume();
        StockHomeGate.refresh(this);
        homeFrameReady();
        interaction();
    }

    @Override protected void onPause() {
        resumed = false;
        StockHomeGate.homeVisible(this, false);
        idle.removeCallbacks(startSaver);
        stopSaver(false);
        if (source != null) source.pause();
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        stopSaver(true);
        interaction();
        if (source != null) source.homeIntent();
        if (home != null) home.reset(true);
        homeFrameReady();
    }

    @Override protected void onDestroy() {
        idle.removeCallbacks(startSaver);
        stopSaver(false);
        if (source != null) source.pause();
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        this.focused = focused;
        if (!focused) { StockHomeGate.homeVisible(this, false); idle.removeCallbacks(startSaver); stopSaver(resumed); }
        else interaction();
        if (source != null) source.focus(focused);
        if (focused) homeFrameReady();
    }

    private void homeFrameReady() {
        if (!HomeMode.keepHisenseApps() || content == null || home == null) return;
        content.postOnAnimation(() -> content.postOnAnimation(() -> {
            if (resumed && focused && content.isAttachedToWindow()) {
                StockHomeGate.homeVisible(this, true);
            }
        }));
    }

    private void interaction() {
        lastInteraction = SystemClock.uptimeMillis();
        idle.removeCallbacks(startSaver);
        if (resumed && focused && home != null && saver == null)
            idle.postDelayed(startSaver, ScreensaverTiming.read(this));
    }

    private void stopSaver(boolean resumeHome) {
        if (saver == null) return;
        saver.stop();
        content.removeView(saver);
        saver = null;
        home.setVisibility(View.VISIBLE);
        if (resumeHome) home.resume();
    }

    @Override public void onUserInteraction() {
        super.onUserInteraction();
        interaction();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (saver != null) { stopSaver(true); interaction(); return true; }
            interaction();
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (saver != null) stopSaver(true);
        interaction();
        return super.dispatchGenericMotionEvent(event);
    }

    void yieldSourceForInputs() { if (source != null) source.yieldInputs(); }

    @Override public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        super.dump(prefix, fd, writer, args);
        writer.println(prefix + "HomeSource: " + HomeSource.status(this));
        writer.println(prefix + "ShelfArtwork: " + (home == null ? "unavailable" : home.shelfArtworkStatus()));
        writer.println(prefix + "DebugButton: " + DebugControls.status(this));
        writer.println(prefix + "StockHomeGate: " + StockHomeGate.status(this));
        writer.println(prefix + "HomeScreenSaver: {\"active\":" + (saver != null)
                + ",\"delayMs\":" + ScreensaverTiming.read(this) + ",\"idleMs\":"
                + Math.max(0, SystemClock.uptimeMillis() - lastInteraction) + "}");
        if (!Prefs.embyRecommendations()) return;
        Recs recs = Recs.get(this);
        EmbyProfile.Snapshot profile = recs.profile();
        try {
            writer.println(prefix + "EmbyProfile: " + new JSONObject().put("name", profile.name)
                    .put("avatarReady", profile.image != null));
            JSONArray series = new JSONArray(), resume = new JSONArray();
            for (Recs.Pick p : recs.now("tv")) series.put(new JSONObject().put("series", p.seriesId)
                    .put("name", p.seriesTitle).put("latestItem", p.embyId));
            for (Recs.Pick p : recs.now("resume")) resume.put(new JSONObject().put("series", p.seriesId)
                    .put("item", p.embyId).put("positionTicks", p.positionTicks));
            writer.println(prefix + "EmbySeries: " + series);
            writer.println(prefix + "EmbyResume: " + resume);
        } catch (Exception ignored) { }
        // Activity dumps require the system DUMP permission. This probe only resolves; it never launches a player.
        if (args != null) for (String arg : args) if (arg.startsWith("emby-probe=")) {
            final String series = arg.substring("emby-probe=".length());
            if (!EmbyRecs.validId(series)) continue;
            playbackProbe = "{\"state\":\"pending\"}";
            final android.content.Context context = getApplicationContext();
            PROBE.execute(() -> {
                try {
                    EmbyPlayback.Target target = EmbyPlayback.target(context, series);
                    JSONObject result = new JSONObject().put("series", series).put("state", target == null ? "empty" : "resolved");
                    if (target != null) result.put("user", target.userId).put("item", target.episode.id)
                            .put("season", target.episode.season).put("episode", target.episode.number)
                            .put("positionTicks", target.episode.position);
                    playbackProbe = result.toString();
                } catch (Exception e) { playbackProbe = "{\"state\":\"error\"}"; }
            });
        }
        writer.println(prefix + "EmbyPlaybackProbe: " + playbackProbe);
        if (args != null) for (String arg : args) if (arg.equals("emby-player-probe") || arg.startsWith("emby-player-probe=")) {
            final String item = arg.startsWith("emby-player-probe=") ? arg.substring("emby-player-probe=".length()) : null;
            playerProbe = "{\"state\":\"pending\"}";
            PROBE.execute(() -> {
                try { playerProbe = EmbyPlayer.probe(getApplicationContext(), item).toString(); }
                catch (Exception e) { playerProbe = "{\"state\":\"error\"}"; }
            });
        }
        writer.println(prefix + "EmbyPlayerProbe: " + playerProbe);
        writer.println(prefix + "EmbyPlayerHandoff: " + EmbyPlayer.lastStatus);
        writer.println(prefix + "EmbyAccountRemote: " + EmbyAccountService.status());
        writer.println(prefix + "ScreenSaverDelayMs: " + ScreensaverTiming.read(this));
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (saver != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            wakeKey = event.getKeyCode();
            stopSaver(true);
        }
        interaction();
        if (wakeKey == event.getKeyCode()) {
            if (event.getAction() == KeyEvent.ACTION_UP) wakeKey = -1;
            return true;
        }
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
