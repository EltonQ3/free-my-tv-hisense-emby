package io.freemytv;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A bounded UI plan: verify the server, prefer a saved login, otherwise use Emby's normal user picker. */
final class EmbyAccountPolicy {
    static final class Labels {
        final Set<String> settings = new HashSet<>(), changeUser = new HashSet<>(), signOut = new HashSet<>();
        final Set<String> manual = new HashSet<>(), forgot = new HashSet<>(), changeServer = new HashSet<>();
        final Set<String> loginPatterns = new HashSet<>(), auth = new HashSet<>();
        void add(Set<String> set, String value) { if (value != null && !value.trim().isEmpty()) set.add(label(value)); }
        Labels() {
            for (String s : new String[] {"Settings", "设置", "設定"}) add(settings, s);
            for (String s : new String[] {"Change User", "更改用户", "更改使用者"}) add(changeUser, s);
            for (String s : new String[] {"Sign Out", "注销退出", "登出"}) add(signOut, s);
            for (String s : new String[] {"Manual Login", "手动登录", "手動登入"}) add(manual, s);
            for (String s : new String[] {"Forgot Password?", "忘记密码？", "忘記密碼？"}) add(forgot, s);
            for (String s : new String[] {"Change Server", "更改服务器", "更改伺服器"}) add(changeServer, s);
            for (String s : new String[] {"Sign in to {0}", "登录 {0}", "登入 {0}"}) add(loginPatterns, s);
            for (String s : new String[] {"Enter your PIN to continue.", "Password", "密码", "密碼"}) add(auth, s);
        }
    }

    static final class Button {
        final int id;
        final String text;
        final boolean menu;
        Button(int id, String text, boolean menu) { this.id = id; this.text = name(text); this.menu = menu; }
    }
    static final class Frame {
        boolean owned, editable;
        boolean tvMenu;
        int accountMenu = -1;
        int scroll = -1;
        final List<Button> buttons = new ArrayList<>();
        final Set<String> text = new HashSet<>();
        Frame button(int id, String text) { buttons.add(new Button(id, text, false)); return this; }
        Frame menuButton(int id, String text) { buttons.add(new Button(id, text, true)); return this; }
        Frame text(String value) { text.add(label(value)); return this; }
    }
    static final class Action {
        static final int NONE = 0, CLICK = 1, SCROLL = 2;
        final int kind, id;
        final String state;
        Action(int kind, int id, String state) { this.kind = kind; this.id = id; this.state = state; }
    }

    private enum Step { START, MENU, SERVER, SAVED_MENU, PICKER, VERIFY }
    private Step step = Step.START;
    private final Labels labels;
    private final String user;
    private final Set<String> serverTitles = new HashSet<>();
    private final long deadline;
    private long nextAction;
    private long missingControlSince = -1;
    private int actions, scrolls;
    private boolean cancelled, verifiedServer;
    private String state = "waiting-ui";

    EmbyAccountPolicy(Labels labels, String user, String server, long now) {
        this.labels = labels; this.user = name(user); deadline = now + 60000;
        if (this.user.isEmpty() || server == null || server.trim().isEmpty() || reserved(this.user)) { cancel("manual-required"); return; }
        for (String pattern : labels.loginPatterns) serverTitles.add(label(pattern.replace("{0}", server)));
    }
    void cancel(String why) { cancelled = true; state = why; }
    String state() { return state; }
    int actionCount() { return actions; }
    boolean stopped() { return cancelled; }
    boolean authentication(Frame f) { return f.editable || intersects(f.text, labels.auth); }

    Action next(Frame f, long now) {
        if (cancelled) return none(state);
        if (now >= deadline) { cancel("expired"); return none(state); }
        if (!f.owned) { cancel("left-emby"); return none(state); }
        if (authentication(f)) return none(state = "auth-required");
        if (step == Step.VERIFY) return none(state = "waiting-session");
        if (actions >= 10) { cancel("manual-required"); return none(state); }
        if (now < nextAction) return none(state);

        boolean menu = has(f, labels.changeUser, true) && has(f, labels.signOut, true);
        boolean picker = has(f, labels.manual, false) && (has(f, labels.forgot, false) || has(f, labels.changeServer, false));
        boolean rightServer = intersects(f.text, serverTitles);
        if (picker && !rightServer) { cancel("server-unverified"); return none(state); }

        switch (step) {
            case START:
                if (menu) {
                    return advance(f, labels.changeUser, now, "checking-server", Step.SERVER);
                }
                if (picker && rightServer) {
                    verifiedServer = true;
                    return advance(f, labels.settings, now, "opening-saved-accounts", Step.SAVED_MENU);
                }
                return advance(f, labels.settings, now, "opening-account-menu", Step.MENU);
            case MENU:
                if (!menu) return none(state);
                return advance(f, labels.changeUser, now, "checking-server", Step.SERVER);
            case SERVER:
                if (menu || !picker || !rightServer) return none(state);
                verifiedServer = true;
                return advance(f, labels.settings, now, "opening-saved-accounts", Step.SAVED_MENU);
            case SAVED_MENU:
                if (!menu || !verifiedServer) return none(state);
                int id = account(f, true);
                if (id == -2) { cancel("ambiguous-account"); return none(state); }
                if (id >= 0) { step = Step.VERIFY; return action(Action.CLICK, id, now, "waiting-session"); }
                return advance(f, labels.changeUser, now, "opening-user-picker", Step.PICKER);
            case PICKER:
                if (menu || !picker || !rightServer || !verifiedServer) return none(state);
                id = account(f, false);
                if (id == -2) { cancel("ambiguous-account"); return none(state); }
                if (id >= 0) { step = Step.VERIFY; return action(Action.CLICK, id, now, "waiting-session"); }
                if (f.scroll >= 0 && scrolls++ < 4) return action(Action.SCROLL, f.scroll, now, "finding-account");
                cancel("account-not-visible"); return none(state);
            default: return none(state);
        }
    }

    private int account(Frame f, boolean menu) {
        int found = -1;
        for (Button b : f.buttons) if (menu == b.menu && user.equals(b.text)) { if (found >= 0) return -2; found = b.id; }
        return found;
    }
    private boolean reserved(String name) {
        String s = label(name);
        return labels.settings.contains(s) || labels.changeUser.contains(s) || labels.signOut.contains(s)
                || labels.manual.contains(s) || labels.forgot.contains(s) || labels.changeServer.contains(s);
    }
    private Action click(Frame f, Set<String> names, long now, String status) {
        int id = -1;
        boolean menu = names == labels.changeUser;
        for (Button b : f.buttons) if (menu == b.menu && names.contains(label(b.text))) {
            if (id >= 0) { cancel("ambiguous-control"); return none(state); }
            id = b.id;
        }
        if (id < 0) {
            // Emby exposes its ready server session before the WebView's header buttons have loaded.
            if (missingControlSince < 0) missingControlSince = now;
            if (now - missingControlSince >= 8000) { cancel("control-not-visible"); return none(state); }
            return none(state = "waiting-controls");
        }
        missingControlSince = -1;
        return action(Action.CLICK, id, now, status);
    }
    private Action advance(Frame f, Set<String> names, long now, String status, Step next) {
        Action result = click(f, names, now, status);
        if (result.kind == Action.CLICK) step = next;
        return result;
    }
    private Action action(int kind, int id, long now, String status) {
        actions++; nextAction = now + 700; state = status; return new Action(kind, id, status);
    }
    private Action none(String status) { return new Action(Action.NONE, -1, status); }
    private static boolean has(Frame f, Set<String> names, boolean menu) {
        for (Button b : f.buttons) if (b.menu == menu && names.contains(label(b.text))) return true;
        return false;
    }
    private static boolean intersects(Set<String> a, Set<String> b) { for (String s : a) if (b.contains(s)) return true; return false; }
    static String name(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC)
                .replaceAll("[\\uE000-\\uF8FF]", "").replace('\u00a0', ' ').trim().replaceAll("\\s+", " ");
    }
    static String label(String value) { return name(value).toLowerCase(Locale.ROOT); }
}
