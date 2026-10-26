package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Explicit, Emby-only UI assistance. Idle unless an internal poster handoff asks for an account selection. */
public final class EmbyAccountService extends AccessibilityService {
    private static volatile EmbyAccountService instance;
    private static volatile EmbyAccountPolicy.Labels labels;
    private static volatile boolean loadingLabels;
    private static volatile String lastStatus = "{}";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Request request;
    private PlayRequest play;
    private volatile ReturnRequest returning;
    private static volatile String returnStatus = "idle";
    private static volatile long returnGeneration;
    static long returnGeneration() { return returnGeneration; }
    private static final class ReturnRequest {
        final EmbyPlayback.Target target;
        final EmbyReturnPolicy policy = new EmbyReturnPolicy();
        volatile boolean cancelled;
        ReturnRequest(EmbyPlayback.Target target) { this.target = target; }
    }
    static void watchReturn(EmbyPlayback.Target target, long generation) {
        EmbyAccountService s = instance;
        if (s != null) s.handler.post(() -> {
            if (generation != returnGeneration || !EmbyPlayer.current(s, target)) return;
            if (s.returning != null) s.returning.cancelled = true;
            s.returning = new ReturnRequest(target); returnStatus = "watching";
        });
    }
    static void cancelReturn() {
        returnGeneration++;
        EmbyAccountService s = instance;
        if (s != null && s.returning != null) {
            s.returning.cancelled = true; s.returning.policy.cancel(); s.returning = null;
            s.handler.removeCallbacks(s.returnTick); returnStatus = "left-player";
        }
    }
    static void backObserved(long at) {
        EmbyAccountService s = instance;
        if (s == null || s.returning == null || !Prefs.embyAutoAccount()) return;
        ReturnRequest r = s.returning;
        if (!r.cancelled && r.policy.back(at, SystemClock.uptimeMillis())) {
            returnStatus = "back-observed"; s.handler.removeCallbacks(s.returnTick); s.handler.postDelayed(s.returnTick, 100);
        }
    }
    private final Runnable returnTick = new Runnable() {
        @Override public void run() {
            ReturnRequest r = returning;
            if (r == null || r.cancelled || !EmbyPlayer.current(EmbyAccountService.this, r.target)
                    || !EmbyNativePlayer.TV.equals(EmbyNativePlayer.preferred(EmbyAccountService.this))) return;
            String screen = tvScreen();
            if (r.policy.show(screen, SystemClock.uptimeMillis())) {
                returnStatus = "opening-details";
                java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
                worker.execute(() -> {
                    try {
                        if (r.cancelled || returning != r || !EmbyPlayer.current(EmbyAccountService.this, r.target)) return;
                        if (EmbyTvTasks.permitted(EmbyAccountService.this)) {
                            EmbyTaskTree.Task task = EmbyTvTasks.find(EmbyAccountService.this);
                            if (task == null || task.playbackPages != 0) throw new Exception();
                        }
                        org.json.JSONArray all = EmbyPlayer.sessions(r.target.config);
                        EmbyPlayerSession.Session nativeSession = EmbyPlayer.session(EmbyAccountService.this, r.target.config, all, EmbyNativePlayer.TV, true);
                        if (nativeSession == null || !r.target.userId.equals(nativeSession.user)) throw new Exception();
                        String current = tvScreen();
                        if (r.cancelled || returning != r || !("home".equals(current) || "details".equals(current))) return;
                        EmbyPlayer.browseItemTv(EmbyAccountService.this, r.target, nativeSession,
                                () -> !r.cancelled && returning == r && ("home".equals(tvScreen()) || "details".equals(tvScreen())));
                        returnStatus = "details-opened";
                    } catch (Exception e) { returnStatus = "native-return"; }
                    finally { handler.post(() -> { if (returning == r) returning = null; }); worker.shutdown(); }
                });
                return;
            }
            if (r.policy.waiting()) handler.postDelayed(this, 120);
        }
    };

    static final class PlayRequest {
        final EmbyPlayback.Target target;
        final EmbyTvPlaybackPolicy policy;
        volatile boolean started, cancelled;
        final long deadline = SystemClock.elapsedRealtime() + 14000;
        PlayRequest(EmbyPlayback.Target target, JSONObject detail) {
            this.target = target;
            policy = new EmbyTvPlaybackPolicy(detail.optString("Name"),
                    "Episode".equals(detail.optString("Type")) ? detail.optInt("ParentIndexNumber", -1) : -1,
                    "Episode".equals(detail.optString("Type")) ? detail.optInt("IndexNumber", -1) : -1,
                    target.episode.position, SystemClock.elapsedRealtime());
        }
    }

    static PlayRequest playTv(EmbyPlayback.Target target, JSONObject detail) {
        EmbyAccountService service = instance;
        if (!available() || !EmbyNativePlayer.TV.equals(EmbyNativePlayer.preferred(service))) return null;
        PlayRequest r = new PlayRequest(target, detail);
        service.handler.post(() -> {
            if (service.play != null) service.play.cancelled = true;
            service.play = r;
            service.handler.post(service.playTick);
        });
        return r;
    }

    private final Runnable playTick = new Runnable() {
        @Override public void run() {
            PlayRequest r = play;
            if (r == null) return;
            if (r.cancelled || SystemClock.elapsedRealtime() >= r.deadline || !EmbyPlayer.current(EmbyAccountService.this, r.target)
                    || !EmbyNativePlayer.TV.equals(EmbyNativePlayer.preferred(EmbyAccountService.this))) {
                r.cancelled = true; play = null; return;
            }
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) { handler.postDelayed(this, 180); return; }
                nodes.add(root);
                boolean owned = EmbyNativePlayer.TV.contentEquals(root.getPackageName() == null ? "" : root.getPackageName());
                EmbyAccountPolicy.Frame frame = owned ? snapshotTv(root, nodes) : new EmbyAccountPolicy.Frame();
                if (owned) nativePlayButtons(nodes, frame);
                String title = ""; boolean player = false;
                for (AccessibilityNodeInfo n : nodes) {
                    if ((EmbyNativePlayer.TV + ":id/fdTitle").equals(n.getViewIdResourceName())) title = text(n);
                    if ((EmbyNativePlayer.TV + ":id/videoSurface").equals(n.getViewIdResourceName())
                            || (EmbyNativePlayer.TV + ":id/video_surface").equals(n.getViewIdResourceName())) player = true;
                }
                if (r.policy.sent() && owned && (player || nativePlaybackWindow)) {
                    r.started = true; play = null; return;
                }
                int button = r.policy.next(owned, frame.editable, !title.isEmpty(), root.getWindowId(), title,
                        frame.text, frame.buttons, SystemClock.elapsedRealtime());
                if (r.policy.cancelled()) { r.cancelled = true; play = null; return; }
                if (button >= 0) {
                    if (!EmbyPlayer.current(EmbyAccountService.this, r.target)) { r.cancelled = true; play = null; return; }
                    AccessibilityNodeInfo n = nodes.get(button);
                    n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    if (!n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { r.cancelled = true; play = null; return; }
                }
            } catch (Exception e) { r.cancelled = true; play = null; return; }
            finally { for (AccessibilityNodeInfo n : nodes) n.recycle(); }
            if (play == r) handler.postDelayed(this, 180);
        }
    };
    // Native details labels sit below sibling ImageButtons, rather than inside clickable parents.
    private static void nativePlayButtons(List<AccessibilityNodeInfo> nodes, EmbyAccountPolicy.Frame frame) {
        frame.buttons.clear();
        for (AccessibilityNodeInfo label : nodes) {
            if (!(EmbyNativePlayer.TV + ":id/label").equals(label.getViewIdResourceName()) || !label.isVisibleToUser()) continue;
            AccessibilityNodeInfo row = buttonRow(label);
            if (row == null) continue;
            try {
                Rect labelBounds = new Rect(); label.getBoundsInScreen(labelBounds);
                int chosen = -1;
                for (int i = 0; i < nodes.size(); i++) {
                    AccessibilityNodeInfo button = nodes.get(i);
                    if (!(EmbyNativePlayer.TV + ":id/imageButton").equals(button.getViewIdResourceName())
                            || !button.isVisibleToUser() || !button.isClickable() || !button.isEnabled()) continue;
                    AccessibilityNodeInfo buttonParent = buttonRow(button);
                    if (buttonParent == null) continue;
                    boolean same;
                    try { same = row.equals(buttonParent); } finally { buttonParent.recycle(); }
                    if (!same) continue;
                    Rect bounds = new Rect(); button.getBoundsInScreen(bounds);
                    if (bounds.isEmpty() || Math.abs(bounds.centerX() - labelBounds.centerX()) > bounds.width() / 3
                            || labelBounds.top < bounds.bottom || labelBounds.top - bounds.bottom > bounds.height()) continue;
                    if (chosen >= 0) { chosen = -2; break; }
                    chosen = i;
                }
                if (chosen >= 0) frame.button(chosen, text(label));
            } finally { row.recycle(); }
        }
    }
    private static AccessibilityNodeInfo buttonRow(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node.getParent();
        for (int depth = 0; current != null && depth < 7; depth++) {
            if ((EmbyNativePlayer.TV + ":id/fdButtonRow").equals(current.getViewIdResourceName())) return current;
            AccessibilityNodeInfo parent = current.getParent(); current.recycle(); current = parent;
        }
        if (current != null) current.recycle();
        return null;
    }
    private boolean nativePlaybackWindow;


    static final class Request {
        final EmbyAccountPolicy policy;
        final EmbyTvAccountPolicy tvPolicy;
        final String player;
        volatile boolean cancelled, foreground, auth, frozen;
        volatile String state = "queued";
        volatile long seenAt;
        final long expiresAt = SystemClock.elapsedRealtime() + 60000;
        long lastAutoAt;
        AccessibilityNodeInfo lastAutoNode;
        Request(String user, String server, String player) {
            this.player = player;
            policy = new EmbyAccountPolicy(labels, user, server, SystemClock.elapsedRealtime());
            tvPolicy = EmbyNativePlayer.TV.equals(player)
                    ? new EmbyTvAccountPolicy(user, true, SystemClock.elapsedRealtime()) : null;
        }
        int actions() { return tvPolicy == null ? policy.actionCount() : tvPolicy.actionCount(); }
        boolean authentication(EmbyAccountPolicy.Frame f) { return tvPolicy == null ? policy.authentication(f) : tvPolicy.authentication(f); }
        boolean stopped() { return tvPolicy == null ? policy.stopped() : tvPolicy.stopped(); }
        synchronized void cancel(String why) { if (!cancelled) { state = why; cancelled = true; } }
        boolean ready() { return !cancelled && foreground && !auth && SystemClock.elapsedRealtime() - seenAt < 800; }
    }

    static final class HomeRequest {
        volatile boolean ready, cancelled;
        int backs; long lastBack;
        final long deadline = SystemClock.elapsedRealtime() + 6000;
    }
    static HomeRequest homeForAccount() {
        EmbyAccountService service = instance;
        if (service == null || !available()) return null;
        HomeRequest r = new HomeRequest();
        service.handler.post(new Runnable() {
            @Override public void run() {
                if (r.cancelled || SystemClock.elapsedRealtime() >= r.deadline) { r.cancelled = true; return; }
                String screen = tvScreen();
                if ("home".equals(screen)) { r.ready = true; return; }
                if ("other".equals(screen) || "auth".equals(screen)) { r.cancelled = true; return; }
                if ("details".equals(screen) && r.backs < 8 && SystemClock.elapsedRealtime() - r.lastBack > 400) {
                    r.backs++; r.lastBack = SystemClock.elapsedRealtime();
                    if (!service.performGlobalAction(GLOBAL_ACTION_BACK)) { r.cancelled = true; return; }
                }
                service.handler.postDelayed(this, 160);
            }
        });
        return r;
    }

    static void dismissTvExit() {
        EmbyAccountService service = instance;
        if (service == null) return;
        service.handler.post(() -> {
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            try {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root == null) return;
                nodes.add(root);
                if (!EmbyNativePlayer.TV.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return;
                EmbyAccountPolicy.Frame frame = service.snapshotTv(root, nodes);
                if (frame.editable || !(frame.text.contains("退出 emby?") || frame.text.contains("退出emby?")
                        || frame.text.contains("exit emby?"))) return;
                int cancel = -1;
                for (EmbyAccountPolicy.Button button : frame.buttons) {
                    String label = EmbyAccountPolicy.label(button.text);
                    if (label.equals("取消") || label.equals("cancel")) { if (cancel >= 0) return; cancel = button.id; }
                }
                if (cancel >= 0) nodes.get(cancel).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } catch (RuntimeException ignored) { }
            finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
        });
    }

    static String tvScreen() {
        EmbyAccountService service = instance;
        if (service == null) return "unavailable";
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return "loading";
        try {
            if (!EmbyNativePlayer.TV.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return "other";
            AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus != null) try { if (focus.isEditable() || focus.isPassword()) return "auth"; }
            finally { focus.recycle(); }
            if (visibleId(root, "userRow")) return "home";
            if (visibleId(root, "fdTitle")) return "details";
            return service.nativePlaybackWindow ? "player" : "loading";
        } catch (Exception e) { return "unavailable"; }
        finally { root.recycle(); }
    }
    private static boolean visibleId(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(EmbyNativePlayer.TV + ":id/" + id);
        try { for (AccessibilityNodeInfo node : nodes) if (node.isVisibleToUser()) return true; }
        finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
        return false;
    }

    static boolean atTvHome() {
        EmbyAccountService s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        try {
            if (!EmbyNativePlayer.TV.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return false;
            List<AccessibilityNodeInfo> headers = root.findAccessibilityNodeInfosByViewId(EmbyNativePlayer.TV + ":id/userRow");
            try { for (AccessibilityNodeInfo n : headers) if (n.isVisibleToUser()) return true; }
            finally { for (AccessibilityNodeInfo n : headers) n.recycle(); }
            return false;
        } finally { root.recycle(); }
    }

    static boolean connected() { return instance != null; }
    static boolean available() { return connected() && labels != null && Prefs.embyAutoAccount(); }
    static String status() {
        try { return new JSONObject(lastStatus).put("connected", connected()).put("labelsReady", labels != null)
                .put("enabled", Prefs.embyAutoAccount()).put("return", returnStatus).toString(); }
        catch (Exception e) { return "{}"; }
    }

    static Request begin(String user, String server, String player) {
        EmbyAccountService service = instance;
        if (service == null || labels == null || !Prefs.embyAutoAccount() || !EmbyNativePlayer.supported(player)) return null;
        Request r = new Request(user, server, player);
        service.handler.post(() -> {
            if (service.request != null) service.finish(service.request, "replaced");
            service.request = r;
            service.handler.removeCallbacks(service.tick);
            service.handler.post(service.tick);
        });
        return r;
    }

    @Override protected void onServiceConnected() {
        instance = this;
        if (labels == null && !loadingLabels) {
            loadingLabels = true;
            java.util.concurrent.ExecutorService loader = Executors.newSingleThreadExecutor();
            loader.execute(() -> { labels = loadLabels(this); loadingLabels = false; loader.shutdown(); });
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && EmbyNativePlayer.TV.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) {
            String activity = event.getClassName() == null ? "" : event.getClassName().toString();
            if (activity.endsWith("Activity")) {
                boolean wasPlaying = nativePlaybackWindow;
                nativePlaybackWindow = activity.equals("tv.emby.embyatv.playback.PlaybackOverlayActivity");
                // Only a playback opened by this handoff owns this one-time return to its details.
                if (wasPlaying && (activity.equals("tv.emby.embyatv.browsing.MainActivity")
                        || activity.equals("tv.emby.embyatv.details.FullDetailsActivity"))) backObserved(event.getEventTime());
            }
        }

        Request r = request;
        if (r == null || r.cancelled || !r.player.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED && !r.auth) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) try {
                if (source.isPassword() || source.isEditable()) return;
                if (!(SystemClock.elapsedRealtime() - r.lastAutoAt < 2000 && insideAuto(source, r.lastAutoNode))) {
                    r.cancel("user-interrupted");
                }
            } finally { source.recycle(); }
        }
        handler.removeCallbacks(tick); handler.postDelayed(tick, 120);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Request r = request;
            if (r == null) return;
            if (SystemClock.elapsedRealtime() >= r.expiresAt) { finish(r, "expired"); return; }
            if (r.cancelled || !Prefs.embyAutoAccount() || !r.player.equals(EmbyNativePlayer.preferred(EmbyAccountService.this))) {
                finish(r, r.state.equals("queued") ? "cancelled" : r.state); return;
            }
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) { r.foreground = false; handler.postDelayed(this, 250); return; }
                nodes.add(root);
                if (!r.player.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                    r.foreground = false;
                    if (r.auth) { handler.postDelayed(this, 250); return; }
                    finish(r, "left-emby"); return;
                }
                r.foreground = true; r.seenAt = SystemClock.elapsedRealtime();
                EmbyAccountPolicy.Frame frame = r.tvPolicy == null ? snapshot(root, nodes) : snapshotTv(root, nodes);
                EmbyAccountPolicy.Action action = r.frozen
                        ? new EmbyAccountPolicy.Action(EmbyAccountPolicy.Action.NONE, -1,
                            r.authentication(frame) ? "auth-required" : "waiting-session")
                        : r.tvPolicy == null ? r.policy.next(frame, SystemClock.elapsedRealtime())
                        : r.tvPolicy.next(frame, SystemClock.elapsedRealtime());
                r.state = action.state; r.auth = "auth-required".equals(action.state);
                if (r.stopped()) { finish(r, action.state); return; }
                if (!r.cancelled && request == r && action.kind != EmbyAccountPolicy.Action.NONE) {
                    AccessibilityNodeInfo node = nodes.get(action.id);
                    if (r.lastAutoNode != null) r.lastAutoNode.recycle();
                    r.lastAutoNode = AccessibilityNodeInfo.obtain(node); r.lastAutoAt = SystemClock.elapsedRealtime();
                    boolean acted;
                    if (action.kind == EmbyAccountPolicy.Action.CLICK) {
                        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        acted = !r.cancelled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    } else acted = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    if (!acted) { finish(r, "control-refused"); return; }
                }
                record(r);
            } catch (Exception ignored) { finish(r, "manual-required"); return; }
            finally { for (AccessibilityNodeInfo n : nodes) n.recycle(); }
            if (request == r && !r.cancelled) handler.postDelayed(this, 250);
        }
    };

    private static boolean insideAuto(AccessibilityNodeInfo node, AccessibilityNodeInfo target) {
        if (target == null) return false;
        AccessibilityNodeInfo at = AccessibilityNodeInfo.obtain(node);
        try {
            for (int i = 0; at != null && i < 8; i++) {
                if (at.equals(target)) return true;
                AccessibilityNodeInfo parent = at.getParent(); at.recycle(); at = parent;
            }
            return false;
        } finally { if (at != null) at.recycle(); }
    }

    private EmbyAccountPolicy.Frame snapshotTv(AccessibilityNodeInfo root, List<AccessibilityNodeInfo> nodes) {
        EmbyAccountPolicy.Frame frame = new EmbyAccountPolicy.Frame(); frame.owned = true;
        List<Integer> parents = new ArrayList<>(); parents.add(-1);
        for (int i = 0; i < nodes.size() && nodes.size() < 800; i++) {
            AccessibilityNodeInfo n = nodes.get(i);
            if (n.getPackageName() != null && !EmbyNativePlayer.TV.contentEquals(n.getPackageName())) continue;
            if (n.isVisibleToUser()) {
                if (n.isPassword() || n.isEditable()) { frame.editable = true; return frame; }
                frame.text(text(n));
                if ((EmbyNativePlayer.TV + ":id/userRow").equals(n.getViewIdResourceName()) && n.isClickable() && n.isEnabled())
                    frame.accountMenu = i;
            }
            if (n.isPassword() || n.isEditable()) continue;
            for (int c = 0; c < n.getChildCount() && nodes.size() < 800; c++) {
                AccessibilityNodeInfo child = n.getChild(c);
                if (child != null) { nodes.add(child); parents.add(i); }
            }
        }
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            AccessibilityNodeInfo n = nodes.get(i);
            if (!n.isVisibleToUser() || n.isPassword() || n.isEditable()) continue;
            if (n.getPackageName() != null && !EmbyNativePlayer.TV.contentEquals(n.getPackageName())) continue;
            String value = text(n);
            if (value.isEmpty()) continue;
            for (int p = i; p >= 0; p = parents.get(p)) {
                AccessibilityNodeInfo candidate = nodes.get(p);
                if (candidate.isClickable() && candidate.isEnabled()) {
                    if (used.add(p)) frame.button(p, value);
                    break;
                }
            }
        }
        boolean server = false, logout = false;
        for (String value : frame.text) {
            if (value.equals("切换服务器") || value.equals("切換伺服器") || value.equals("change server") || value.equals("switch server")) server = true;
            if (value.equals("注销退出") || value.equals("登出") || value.equals("sign out") || value.equals("log out")) logout = true;
        }
        frame.tvMenu = server && logout;
        return frame;
    }

    private EmbyAccountPolicy.Frame snapshot(AccessibilityNodeInfo root, List<AccessibilityNodeInfo> nodes) {
        EmbyAccountPolicy.Frame frame = new EmbyAccountPolicy.Frame(); frame.owned = true;
        List<Integer> parents = new ArrayList<>(); parents.add(-1);
        for (int i = 0; i < nodes.size() && nodes.size() < 800; i++) {
            AccessibilityNodeInfo n = nodes.get(i);
            if (n.getPackageName() != null && !"com.mb.android".contentEquals(n.getPackageName())) continue;
            Rect bounds = new Rect(); n.getBoundsInScreen(bounds);
            if (n.isVisibleToUser() && !bounds.isEmpty()) {
                if (n.isPassword() || n.isEditable()) { frame.editable = true; return frame; }
                else {
                    String text = text(n); frame.text(text);
                    if (n.isClickable() && n.isEnabled() && n.getClassName() != null
                            && n.getClassName().toString().endsWith("Button")) frame.button(i, text);
                    if (n.isScrollable()) frame.scroll = frame.scroll == -1 ? i : -2;
                }
            }
            if (n.isPassword() || n.isEditable()) continue;
            for (int c = 0; c < n.getChildCount() && nodes.size() < 800; c++) {
                AccessibilityNodeInfo child = n.getChild(c); if (child != null) { nodes.add(child); parents.add(i); }
            }
        }
        int change = -1, signOut = -1;
        for (EmbyAccountPolicy.Button b : frame.buttons) {
            if (labels.changeUser.contains(EmbyAccountPolicy.label(b.text))) change = change == -1 ? b.id : -2;
            if (labels.signOut.contains(EmbyAccountPolicy.label(b.text))) signOut = signOut == -1 ? b.id : -2;
        }
        int menu = -1;
        if (change >= 0 && signOut >= 0) for (int p = parents.get(change); p > 0; p = parents.get(p)) {
            if (inside(signOut, p, parents)) { menu = p; break; }
        }
        if (menu >= 0) for (EmbyAccountPolicy.Button b : frame.buttons) {
            if (inside(b.id, menu, parents) && (labels.settings.contains(EmbyAccountPolicy.label(b.text))
                    || labels.manual.contains(EmbyAccountPolicy.label(b.text)))) { menu = -1; break; }
        }
        if (menu >= 0) for (int i = 0; i < frame.buttons.size(); i++) {
            EmbyAccountPolicy.Button b = frame.buttons.get(i);
            if (inside(b.id, menu, parents)) frame.buttons.set(i, new EmbyAccountPolicy.Button(b.id, b.text, true));
        }
        return frame;
    }
    private static boolean inside(int child, int parent, List<Integer> parents) {
        for (int n = child; n >= 0; n = parents.get(n)) if (n == parent) return true;
        return false;
    }
    private static String text(AccessibilityNodeInfo n) {
        CharSequence value = n.getText();
        if (value == null || value.length() == 0) value = n.getContentDescription();
        return value == null ? "" : value.toString();
    }
    private void record(Request r) {
        try { lastStatus = new JSONObject().put("state", r.state).put("actions", r.actions()).put("playerPackage", r.player).toString(); }
        catch (Exception ignored) { }
    }
    private void finish(Request r, String why) {
        r.cancel(why); r.policy.cancel(why); if (r.tvPolicy != null) r.tvPolicy.cancel(why); record(r);
        if (r.lastAutoNode != null) { r.lastAutoNode.recycle(); r.lastAutoNode = null; }
        if (request == r) request = null;
        handler.removeCallbacks(tick);
    }
    @Override public void onInterrupt() { cancelReturn(); if (play != null) play.cancelled = true; if (request != null) finish(request, "interrupted"); }
    @Override public boolean onUnbind(android.content.Intent intent) {
        cancelReturn();
        if (request != null) finish(request, "disconnected");
        if (play != null) play.cancelled = true;
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        cancelReturn();
        if (request != null) finish(request, "disconnected");
        if (play != null) play.cancelled = true;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private static EmbyAccountPolicy.Labels loadLabels(Context context) {
        EmbyAccountPolicy.Labels result = new EmbyAccountPolicy.Labels();
        try {
            android.content.res.AssetManager assets = context.createPackageContext("com.mb.android", 0).getAssets();
            String folder = "www/modules/common/strings";
            String[] files = assets.list(folder);
            if (files == null) return result;
            int count = 0;
            for (String file : files) {
                if (!file.matches("[A-Za-z0-9-]+\\.json") || count++ > 100) continue;
                try (InputStream in = assets.open(folder + "/" + file)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] bytes = new byte[8192]; int n;
                    while ((n = in.read(bytes)) != -1) { if (out.size() + n > 512 * 1024) throw new java.io.IOException(); out.write(bytes, 0, n); }
                    JSONObject o = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\uFEFF", ""));
                    result.add(result.settings, o.optString("Settings"));
                    result.add(result.changeUser, o.optString("HeaderChangeUser"));
                    result.add(result.signOut, o.optString("HeaderSignOut"));
                    result.add(result.manual, o.optString("HeaderManualLogin"));
                    result.add(result.forgot, o.optString("HeaderForgotPassword"));
                    result.add(result.changeServer, o.optString("HeaderChangeServer"));
                    result.add(result.loginPatterns, o.optString("SignIntoServerName"));
                    result.add(result.auth, o.optString("EnterYourPinToContinue"));
                    result.add(result.auth, o.optString("LabelPassword"));
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return result;
    }
}
