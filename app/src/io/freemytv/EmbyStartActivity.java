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

/** Internal, bounded handoff to the selected official player and its exact resume position. */
public final class EmbyStartActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;
    private boolean launched, pausedAfterLaunch;
    private EmbyPlayback.Target prepared;
    private volatile EmbyAccountService.Request accountRequest;
    private volatile EmbyAccountService.PlayRequest playRequest;
    private volatile EmbyAccountService.HomeRequest homeRequest;
    private EmbyTaskTree.Task nativeTask;
    private long nativeTaskReadAt;
    private boolean reusedTv, directTv = true;
    private long returnGeneration;
    private String requestedItem, requestedSeries;
    private boolean requestedResolveSeries;
    private boolean accountOnly;
    private String playerPackage;
    private volatile String stage = "preparing", errorType = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        returnGeneration = EmbyAccountService.returnGeneration();
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
                if (EmbyNativePlayer.TV.equals(EmbyNativePlayer.preferred(this))) {
                    directTv = false;
                    if (EmbyTvTasks.permitted(this)) try {
                        int pid = EmbyTvTasks.processId(this);
                        nativeTask = EmbyTvTasks.find(this); nativeTaskReadAt = SystemClock.elapsedRealtime();
                        directTv = EmbyTvTasks.directAllowed(this, pid, nativeTask != null);
                    } catch (Exception unavailable) {
                        nativeTask = null; EmbyTvTasks.untrackedStartup(this);
                    }
                    else EmbyTvTasks.untrackedStartup(this);
                }
                runOnUiThread(() -> launch(target));
            } catch (Exception e) { errorType = e.getClass().getSimpleName(); fail(Ui.tr("Connection failed")); }
        });
    }

    private void launch(EmbyPlayback.Target target) {
        if (cancelled || isFinishing() || !EmbyPlayer.current(this, target)) { finish(); return; }
        if (!hasWindowFocus()) { prepared = target; return; }
        prepared = null;
        playerPackage = EmbyNativePlayer.preferred(this);
        if (EmbyNativePlayer.TV.equals(playerPackage) && nativeTask != null) {
            try {
                stage = "restoring-native-task";
                launched = true; reusedTv = true;
                EmbyTvTasks.restore(this, nativeTask, nativeTaskReadAt);
                io.execute(() -> handoff(target));
            } catch (Exception e) { errorType = e.getClass().getSimpleName(); fail(Ui.tr("Connection failed")); }
            return;
        }
        Intent nativeHome = HomeApps.launchIntent(getPackageManager(), playerPackage);
        if (nativeHome == null) { fail(Ui.trf("Couldn't open {app}.", "app", "Emby")); return; }
        try {
            // No emby://play link here: that native handler drops the start position.
            launched = true;
            stage = "opening-emby";
            if (EmbyNativePlayer.ANDROID.equals(playerPackage))
                nativeHome.setAction("ACTION_APPSHORTCUT").putExtra("shortcutid", "home");
            nativeHome.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | (EmbyNativePlayer.TV.equals(playerPackage)
                    ? Intent.FLAG_ACTIVITY_CLEAR_TASK : Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            startActivity(nativeHome);
            io.execute(() -> handoff(target));
        } catch (Exception e) { launched = false; fail(Ui.tr("Connection failed")); }
    }

    private EmbyPlayerSession.Session currentSession(JSONObject config) throws Exception {
        boolean verified = false;
        if (EmbyNativePlayer.TV.equals(playerPackage)) {
            String screen = EmbyAccountService.tvScreen();
            if (!("home".equals(screen) || "details".equals(screen) || "player".equals(screen)))
                return null;
            if (directTv && EmbyTvTasks.permitted(this)) {
                EmbyTaskTree.Task task = EmbyTvTasks.find(this);
                if (task == null) return null;
                if (task.playbackPages > 1) throw new Exception("Duplicate native players");
            }
            verified = true;
        }
        return EmbyPlayer.session(this, config, EmbyPlayer.sessions(config), playerPackage, verified);
    }

    private void handoff(EmbyPlayback.Target target) {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        try {
            Thread.sleep(650);
            if (EmbyNativePlayer.TV.equals(playerPackage)) EmbyAccountService.dismissTvExit();
            if (reusedTv && nativeTask.topActivity.equals("tv.emby.embyatv.playback.PlaybackOverlayActivity")) {
                stage = "closing-previous-player";
                long stopDeadline = SystemClock.elapsedRealtime() + 5000;
                while (!cancelled && !"player".equals(EmbyAccountService.tvScreen()) && SystemClock.elapsedRealtime() < stopDeadline)
                    Thread.sleep(120);
                if (cancelled || !"player".equals(EmbyAccountService.tvScreen())) throw new Exception("Native player not foreground");
                EmbyPlayerSession.Session previous = currentSession(target.config);
                EmbyPlayer.stopTv(this, target, previous);
                while (!cancelled && "player".equals(EmbyAccountService.tvScreen()) && SystemClock.elapsedRealtime() < stopDeadline)
                    Thread.sleep(150);
                if (cancelled) return;
                if ("player".equals(EmbyAccountService.tvScreen())) throw new Exception("Native player did not stop");
                Thread.sleep(450);
            }
            while (!cancelled && SystemClock.elapsedRealtime() < deadline) {
                stage = "waiting-player-session";
                if (!EmbyPlayer.current(this, target) || !playerPackage.equals(EmbyNativePlayer.preferred(this)))
                    throw new Exception("Connection changed");
                if (accountRequest != null && accountRequest.cancelled)
                    throw new Exception("Account selection cancelled");
                if (EmbyNativePlayer.TV.equals(playerPackage)
                        && !("home".equals(EmbyAccountService.tvScreen()) || "details".equals(EmbyAccountService.tvScreen()))) {
                    Thread.sleep(250); continue;
                }
                EmbyPlayerSession.Session session = currentSession(target.config);
                if (cancelled) return;
                if (session != null && (session.ready || accountOnly || EmbyNativePlayer.TV.equals(playerPackage))) {
                    if (!target.userId.equals(session.user)) {
                        if (accountRequest == null) {
                            if (!EmbyAccountService.available()) {
                                fail(Ui.trf("Switch Emby to {user}, then open this poster again.", "user", target.config.optString("user_name")));
                                return;
                            }
                            if (EmbyNativePlayer.TV.equals(playerPackage) && !EmbyAccountService.atTvHome()) {
                                stage = "opening-native-account-menu";
                                homeRequest = EmbyAccountService.homeForAccount();
                                if (homeRequest == null) throw new Exception("Native home unavailable");
                                long homeDeadline = SystemClock.elapsedRealtime() + 6500;
                                while (!cancelled && !homeRequest.ready && !homeRequest.cancelled && SystemClock.elapsedRealtime() < homeDeadline)
                                    Thread.sleep(120);
                                if (cancelled) return;
                                if (!homeRequest.ready) throw new Exception("Native home unavailable");
                            }
                            stage = "checking-server";
                            JSONObject info = new JSONObject(EmbyRecs.request(target.config, "/System/Info"));
                            if (!target.serverId.equals(info.optString("Id"))) { stage = "server-mismatch"; throw new Exception("Server changed"); }
                            stage = "starting-account-control";
                            accountRequest = EmbyAccountService.begin(target.config.optString("user_name"), info.optString("ServerName"), playerPackage);
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
                        EmbyPlayer.lastStatus = new JSONObject().put("state", "account-verified").put("user", target.userId)
                                .put("playerPackage", playerPackage).toString();
                        if (accountRequest != null) accountRequest.cancel("completed");
                        runOnUiThread(this::finish); return;
                    }
                    if (!session.ready) { Thread.sleep(350); continue; }
                    // A PIN or account switch can take time: refresh the actual item/progress before dispatch.
                    stage = "refreshing-progress";
                    EmbyPlayback.Target fresh = EmbyPlayback.forItem(this, requestedItem, requestedSeries, requestedResolveSeries);
                    if (fresh == null) { fail(Ui.tr("Nothing to continue watching")); return; }
                    if (!target.userId.equals(fresh.userId) || !EmbyPlayer.current(this, fresh) || cancelled)
                        throw new Exception("Connection changed");
                    if (!playerPackage.equals(EmbyNativePlayer.preferred(this))) throw new Exception("Player changed");
                    stage = "rechecking-player";
                    session = currentSession(fresh.config);
                    if (cancelled) return;
                    if (session == null || !session.ready || !fresh.userId.equals(session.user)) {
                        Thread.sleep(250); continue;
                    }
                    stage = "rechecking-account";
                    // The fresh native profile and foreground above are authoritative after network I/O.
                    if (accountRequest != null && accountRequest.cancelled) throw new Exception("Account control interrupted");
                    target = fresh;
                    EmbyPlayer.pin(this, target, session);
                    if (cancelled) return;
                    stage = "dispatching-play";
                    if (EmbyNativePlayer.TV.equals(playerPackage)
                            && !("home".equals(EmbyAccountService.tvScreen()) || "details".equals(EmbyAccountService.tvScreen())))
                        throw new Exception("Native foreground changed");
                    if (EmbyNativePlayer.TV.equals(playerPackage) && !directTv) {
                        // A live process without a task may retain Startup's old SDK callbacks.
                        // Decide before any PlayNow; never retry a possibly delivered play command.
                        if (!EmbyAccountService.available()) throw new Exception("Native playback assistance unavailable");
                        if (accountRequest != null) accountRequest.cancel("completed");
                        JSONObject detail = EmbyPlayer.browseItemTv(this, target, session,
                                () -> !cancelled && ("home".equals(EmbyAccountService.tvScreen()) || "details".equals(EmbyAccountService.tvScreen())));
                        playRequest = EmbyAccountService.playTv(target, detail);
                        if (playRequest == null) throw new Exception("Native playback assistance unavailable");
                        long uiDeadline = SystemClock.elapsedRealtime() + 15000;
                        while (!cancelled && !playRequest.started && !playRequest.cancelled && SystemClock.elapsedRealtime() < uiDeadline)
                            Thread.sleep(150);
                        if (cancelled) return;
                        if (!playRequest.started || !EmbyPlayer.current(this, target)) throw new Exception("Native playback not confirmed");
                    } else EmbyPlayer.play(this, target, session);
                    if (EmbyNativePlayer.TV.equals(playerPackage)) {
                        stage = "confirming-native-player";
                        long playDeadline = SystemClock.elapsedRealtime() + 7000;
                        boolean started = false;
                        while (!cancelled && SystemClock.elapsedRealtime() < playDeadline) {
                            if (!directTv || !EmbyTvTasks.permitted(this)) {
                                started = playRequest != null && playRequest.started && "player".equals(EmbyAccountService.tvScreen());
                                if (started) break;
                                Thread.sleep(180); continue;
                            }
                            EmbyTaskTree.Task playing = EmbyTvTasks.find(this);
                            if (playing != null && playing.playbackPages > 1) throw new Exception("Duplicate native players");
                            if (playing != null && playing.playbackPages == 1
                                    && playing.topActivity.equals("tv.emby.embyatv.playback.PlaybackOverlayActivity")) { started = true; break; }
                            Thread.sleep(180);
                        }
                        if (cancelled) return;
                        if (!started) throw new Exception("Native playback not confirmed");
                        EmbyAccountService.watchReturn(target, returnGeneration);
                    }
                    EmbyPlayer.lastStatus = new JSONObject().put("state", "dispatched").put("item", target.episode.id)
                            .put("route", EmbyNativePlayer.TV.equals(playerPackage) && !directTv ? "native-resume-button" : "direct-play").put("reusedTask", reusedTv).put("dispatchCount", EmbyPlayer.dispatchCount())
                            .put("positionTicks", target.episode.position).put("user", target.userId)
                            .put("playerPackage", playerPackage).toString();
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
        if (playRequest != null) playRequest.cancelled = true;
        if (homeRequest != null) homeRequest.cancelled = true;
        io.shutdownNow(); super.onDestroy();
    }
}
