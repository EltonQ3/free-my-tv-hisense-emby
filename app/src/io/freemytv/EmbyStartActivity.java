package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Internal, short-lived relay. Launches only Emby's home; remote PlayNow carries the exact resume ticks. */
public final class EmbyStartActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;
    private boolean launched, pausedAfterLaunch;
    private EmbyPlayback.Target prepared;
    private volatile EmbyAccountService.Request accountRequest;
    private String requestedItem, requestedSeries;
    private boolean requestedResolveSeries;
    private boolean accountOnly;
    private volatile String stage = "preparing", errorType = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        TextView text = new TextView(this);
        text.setText("Emby\n" + Ui.tr("Connecting…")); text.setTextColor(Color.WHITE);
        text.setTextSize(24); text.setGravity(Gravity.CENTER); text.setBackgroundColor(0xff111217);
        text.setSoundEffectsEnabled(false); setContentView(text);
        if (!Prefs.embyRecommendations() || state != null) { finish(); return; }
        final String item = getIntent().getStringExtra("item"), series = getIntent().getStringExtra("series");
        final boolean resolveSeries = getIntent().getBooleanExtra("resolveSeries", false);
        accountOnly = getIntent().getBooleanExtra("accountOnly", false);
        requestedItem = item; requestedSeries = series; requestedResolveSeries = resolveSeries;
        io.execute(() -> {
            try {
                EmbyPlayback.Target target = accountOnly ? EmbyPlayback.account(this)
                        : EmbyPlayback.forItem(this, item, series, resolveSeries);
                if (cancelled) return;
                if (target == null) { fail(Ui.tr("Nothing to continue watching")); return; }
                runOnUiThread(() -> launch(target));
            } catch (Exception e) { errorType = e.getClass().getSimpleName(); fail(Ui.tr("Connection failed")); }
        });
    }

    private void launch(EmbyPlayback.Target target) {
        if (cancelled || isFinishing() || !EmbyPlayer.current(this, target)) { finish(); return; }
        if (!hasWindowFocus()) { prepared = target; return; }
        prepared = null;
        Intent nativeHome = HomeApps.launchIntent(getPackageManager(), "com.mb.android");
        if (nativeHome == null) { fail(Ui.trf("Couldn't open {app}.", "app", "Emby")); return; }
        try {
            // No emby://play link here: that native handler drops the start position.
            launched = true;
            stage = "opening-emby";
            nativeHome.setAction("ACTION_APPSHORTCUT").putExtra("shortcutid", "home");
            nativeHome.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(nativeHome);
            io.execute(() -> handoff(target));
        } catch (Exception e) { launched = false; fail(Ui.tr("Connection failed")); }
    }

    private void handoff(EmbyPlayback.Target target) {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        try {
            Thread.sleep(650);
            while (!cancelled && SystemClock.elapsedRealtime() < deadline) {
                stage = "waiting-player-session";
                if (!EmbyPlayer.current(this, target)) throw new Exception("Connection changed");
                EmbyPlayerSession.Session session = EmbyPlayer.session(this, target.config, EmbyPlayer.sessions(target.config));
                if (cancelled) return;
                if (session != null && session.ready) {
                    if (!target.userId.equals(session.user)) {
                        if (accountRequest == null) {
                            if (!EmbyAccountService.available()) {
                                fail(Ui.trf("Switch Emby to {user}, then open this poster again.", "user", target.config.optString("user_name")));
                                return;
                            }
                            stage = "checking-server";
                            JSONObject info = new JSONObject(EmbyRecs.request(target.config, "/System/Info"));
                            if (!target.serverId.equals(info.optString("Id"))) { stage = "server-mismatch"; throw new Exception("Server changed"); }
                            stage = "starting-account-control";
                            accountRequest = EmbyAccountService.begin(target.config.optString("user_name"), info.optString("ServerName"));
                            if (accountRequest == null) throw new Exception("Account control unavailable");
                            deadline = SystemClock.elapsedRealtime() + 60000;
                        }
                        if (accountRequest.cancelled) { stage = "account-control-" + accountRequest.state; throw new Exception("Account selection cancelled"); }
                        Thread.sleep(350); continue;
                    }
                    if (accountRequest != null && !accountRequest.ready()) {
                        if (accountRequest.cancelled) throw new Exception("Account selection cancelled");
                        Thread.sleep(350); continue;
                    }
                    if (accountRequest != null) accountRequest.frozen = true;
                    if (!EmbyPlayer.current(this, target) || cancelled) return;
                    if (accountOnly) {
                        EmbyPlayer.lastStatus = new JSONObject().put("state", "account-verified").put("user", target.userId).toString();
                        if (accountRequest != null) accountRequest.cancel("completed");
                        runOnUiThread(this::finish); return;
                    }
                    // A PIN or account switch can take time: refresh the actual item/progress before dispatch.
                    stage = "refreshing-progress";
                    EmbyPlayback.Target fresh = EmbyPlayback.forItem(this, requestedItem, requestedSeries, requestedResolveSeries);
                    if (fresh == null) { fail(Ui.tr("Nothing to continue watching")); return; }
                    if (!target.userId.equals(fresh.userId) || !EmbyPlayer.current(this, fresh) || cancelled)
                        throw new Exception("Connection changed");
                    session = EmbyPlayer.session(this, fresh.config, EmbyPlayer.sessions(fresh.config));
                    if (session == null || !session.ready || !fresh.userId.equals(session.user) || cancelled)
                        throw new Exception("Player changed");
                    if (accountRequest != null && !accountRequest.ready()) throw new Exception("Account control interrupted");
                    target = fresh;
                    EmbyPlayer.pin(this, target, session);
                    if (cancelled) return;
                    stage = "dispatching-play";
                    EmbyPlayer.play(this, target, session);
                    EmbyPlayer.lastStatus = new JSONObject().put("state", "dispatched").put("item", target.episode.id)
                            .put("positionTicks", target.episode.position).put("user", target.userId).toString();
                    if (accountRequest != null) accountRequest.cancel("completed");
                    runOnUiThread(this::finish);
                    return;
                }
                Thread.sleep(500);
            }
            if (!cancelled) fail(Ui.tr("Open Emby, sign in, then open this poster again."));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) { errorType = e.getClass().getSimpleName(); if (!cancelled) fail(Ui.tr("Connection failed")); }
        finally {
            if (accountRequest != null) accountRequest.cancel("cancelled");
            runOnUiThread(this::finish);
        }
    }

    private void fail(String message) {
        if (cancelled) return;
        try { EmbyPlayer.lastStatus = new JSONObject().put("state", "not-dispatched").put("stage", stage).put("errorType", errorType).toString(); }
        catch (Exception ignored) { }
        runOnUiThread(() -> { if (!cancelled && !isFinishing()) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); finish(); });
    }

    @Override protected void onResume() {
        super.onResume();
        // Back to the relay or HOME cancels any delayed send; the user must never get a late surprise play.
        if (pausedAfterLaunch) { cancelled = true; finish(); }
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && prepared != null && !launched) launch(prepared);
    }
    @Override protected void onPause() {
        if (launched) pausedAfterLaunch = true; else cancelled = true;
        super.onPause();
    }
    @Override protected void onDestroy() {
        cancelled = true;
        if (accountRequest != null) accountRequest.cancel("cancelled");
        io.shutdownNow(); super.onDestroy();
    }
}
