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

    static final class Request {
        final EmbyAccountPolicy policy;
        volatile boolean cancelled, foreground, auth, frozen;
        volatile String state = "queued";
        volatile long seenAt;
        final long expiresAt = SystemClock.elapsedRealtime() + 60000;
        long lastAutoAt;
        AccessibilityNodeInfo lastAutoNode;
        Request(String user, String server) { policy = new EmbyAccountPolicy(labels, user, server, SystemClock.elapsedRealtime()); }
        synchronized void cancel(String why) { if (!cancelled) { state = why; cancelled = true; } }
        boolean ready() { return !cancelled && foreground && !auth && SystemClock.elapsedRealtime() - seenAt < 800; }
    }

    static boolean connected() { return instance != null; }
    static boolean available() { return connected() && labels != null && Prefs.embyAutoAccount(); }
    static String status() {
        try { return new JSONObject(lastStatus).put("connected", connected()).put("labelsReady", labels != null)
                .put("enabled", Prefs.embyAutoAccount()).toString(); }
        catch (Exception e) { return "{}"; }
    }

    static Request begin(String user, String server) {
        EmbyAccountService service = instance;
        if (service == null || labels == null || !Prefs.embyAutoAccount()) return null;
        Request r = new Request(user, server);
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
        Request r = request;
        if (r == null || r.cancelled || !"com.mb.android".contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED && !r.auth) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) try {
                if (source.isPassword() || source.isEditable()) return;
                if (!(SystemClock.elapsedRealtime() - r.lastAutoAt < 2000 && source.equals(r.lastAutoNode))) {
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
            if (r.cancelled || !Prefs.embyAutoAccount()) { finish(r, r.state.equals("queued") ? "cancelled" : r.state); return; }
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) { r.foreground = false; handler.postDelayed(this, 250); return; }
                nodes.add(root);
                if (!"com.mb.android".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                    r.foreground = false;
                    if (r.auth) { handler.postDelayed(this, 250); return; }
                    finish(r, "left-emby"); return;
                }
                r.foreground = true; r.seenAt = SystemClock.elapsedRealtime();
                EmbyAccountPolicy.Frame frame = snapshot(root, nodes);
                EmbyAccountPolicy.Action action = r.frozen
                        ? new EmbyAccountPolicy.Action(EmbyAccountPolicy.Action.NONE, -1,
                            r.policy.authentication(frame) ? "auth-required" : "waiting-session")
                        : r.policy.next(frame, SystemClock.elapsedRealtime());
                r.state = action.state; r.auth = "auth-required".equals(action.state);
                if (r.policy.stopped()) { finish(r, action.state); return; }
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
        try { lastStatus = new JSONObject().put("state", r.state).put("actions", r.policy.actionCount()).toString(); }
        catch (Exception ignored) { }
    }
    private void finish(Request r, String why) {
        r.cancel(why); r.policy.cancel(why); record(r);
        if (r.lastAutoNode != null) { r.lastAutoNode.recycle(); r.lastAutoNode = null; }
        if (request == r) request = null;
        handler.removeCallbacks(tick);
    }
    @Override public void onInterrupt() { if (request != null) finish(request, "interrupted"); }
    @Override public boolean onUnbind(android.content.Intent intent) {
        if (request != null) finish(request, "disconnected");
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        if (request != null) finish(request, "disconnected");
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
