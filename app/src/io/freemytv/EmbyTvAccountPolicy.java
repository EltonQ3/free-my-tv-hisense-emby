package io.freemytv;

/** Native TV account menu only. Never signs out, switches servers or enters authentication. */
final class EmbyTvAccountPolicy {
    private final String user;
    private final long deadline;
    private long nextAction, missingSince = -1;
    private int actions;
    private boolean cancelled, waitingSession;
    private String state = "waiting-ui";
    EmbyTvAccountPolicy(String user, boolean serverVerified, long now) {
        this.user = EmbyAccountPolicy.name(user); deadline = now + 60000;
        if (!serverVerified || this.user.isEmpty()) cancel("server-unverified");
    }
    void cancel(String why) { cancelled = true; state = why; }
    boolean stopped() { return cancelled; }
    int actionCount() { return actions; }
    String state() { return state; }
    boolean authentication(EmbyAccountPolicy.Frame frame) {
        if (frame.editable) return true;
        for (String s : new String[] {"Password", "密码", "密碼", "Enter your PIN to continue.", "请输入 PIN", "請輸入 PIN"})
            if (frame.text.contains(EmbyAccountPolicy.label(s))) return true;
        return false;
    }
    static boolean switchTo(String label, String user) {
        String value = EmbyAccountPolicy.name(label), name = EmbyAccountPolicy.name(user);
        for (String prefix : new String[] {"Switch to ", "切换到 ", "切換到 "})
            if (value.equals(prefix + name)) return true;
        return false;
    }
    EmbyAccountPolicy.Action next(EmbyAccountPolicy.Frame f, long now) {
        if (cancelled) return none(state);
        if (now >= deadline) { cancel("expired"); return none(state); }
        if (!f.owned) { cancel("left-emby"); return none(state); }
        if (authentication(f)) return none(state = "auth-required");
        if (waitingSession) return none(state = "waiting-session");
        if (now < nextAction) return none(state);
        if (actions >= 4) { cancel("manual-required"); return none(state); }
        if (f.tvMenu) {
            int found = -1;
            for (EmbyAccountPolicy.Button b : f.buttons) if (switchTo(b.text, user)) {
                if (found >= 0) { cancel("ambiguous-account"); return none(state); }
                found = b.id;
            }
            if (found < 0) { cancel("account-not-visible"); return none(state); }
            waitingSession = true;
            return click(found, now, "waiting-session");
        }
        if (f.accountMenu >= 0) return click(f.accountMenu, now, "opening-account-menu");
        if (missingSince < 0) missingSince = now;
        if (now - missingSince > 8000) cancel("control-not-visible");
        return none(state);
    }
    private EmbyAccountPolicy.Action click(int id, long now, String why) {
        actions++; nextAction = now + 900; state = why;
        return new EmbyAccountPolicy.Action(EmbyAccountPolicy.Action.CLICK, id, why);
    }
    private EmbyAccountPolicy.Action none(String why) {
        return new EmbyAccountPolicy.Action(EmbyAccountPolicy.Action.NONE, -1, why);
    }
}
