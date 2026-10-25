package io.freemytv;

/** Standalone JVM checks; no Android, network, account credentials or television required. */
public final class EmbyAccountPolicyTest {
    private static int checks;
    private static void check(boolean ok, String reason) { checks++; if (!ok) throw new AssertionError(reason); }
    private static EmbyAccountPolicy policy() { return new EmbyAccountPolicy(new EmbyAccountPolicy.Labels(), "Account A", "My Server", 0); }
    private static EmbyAccountPolicy.Frame home() { return frame().button(1, "Settings").button(8, "Account A"); }
    private static EmbyAccountPolicy.Frame frame() { EmbyAccountPolicy.Frame f = new EmbyAccountPolicy.Frame(); f.owned = true; return f; }
    private static EmbyAccountPolicy.Frame menu(boolean saved) {
        EmbyAccountPolicy.Frame f = frame().button(1, "Settings").menuButton(2, "Change User").menuButton(3, "Sign Out");
        if (saved) f.menuButton(30, "Account A");
        return f;
    }
    private static EmbyAccountPolicy.Frame picker(String server) {
        return frame().text("Sign in to " + server).button(1, "Settings").button(4, "Manual Login")
                .button(5, "Forgot Password?").button(6, "Change Server").button(40, "Account A");
    }
    private static void click(EmbyAccountPolicy p, EmbyAccountPolicy.Frame f, long at, int expected, String why) {
        EmbyAccountPolicy.Action a = p.next(f, at);
        check(a.kind == EmbyAccountPolicy.Action.CLICK && a.id == expected, why);
        check(!p.stopped(), "valid selection should stay active");
    }
    public static void main(String[] args) {
        EmbyAccountPolicy p = policy();
        click(p, home(), 0, 1, "media with the same name must never be selected on the home screen");
        check(p.next(home(), 100).kind == 0, "throttle repeated accessibility events");
        click(p, menu(true), 1000, 2, "verify server before using a saved user shortcut");
        click(p, picker("My Server"), 2000, 1, "after verifying server, open saved account menu");
        EmbyAccountPolicy.Frame overlay = menu(true).button(40, "Account A").button(4, "Manual Login")
                .button(5, "Forgot Password?").text("Sign in to My Server");
        click(p, overlay, 3000, 30, "select menu shortcut, not the identically named background user tile");
        check(p.actionCount() == 4, "cached-login path has four bounded actions");
        check(p.next(overlay, 4000).kind == 0, "never click a selected account twice while server verification is pending");

        EmbyAccountPolicy.Frame auth = frame(); auth.editable = true;
        check(p.next(auth, 5000).kind == 0 && p.state().equals("auth-required"), "password or editable input stays manual");
        check(p.next(home(), 6000).kind == 0 && p.state().equals("waiting-session"), "after login, wait for server identity instead of replaying UI actions");
        p.cancel("completed"); check(p.next(home(), 7000).kind == 0, "completion cancels UI control");

        p = policy(); click(p, menu(false), 0, 2, "already-open menu is supported");
        click(p, picker("My Server"), 1000, 1, "check saved logins first");
        click(p, menu(false), 2000, 2, "uncached profile uses the normal login picker");
        click(p, picker("My Server"), 3000, 40, "normal picker selects requested visible user");
        check(p.next(frame().text("Enter your PIN to continue."), 4000).kind == 0 && p.state().equals("auth-required"), "PIN keypad never gets automatic input");

        p = policy(); click(p, menu(true), 0, 2, "server verification required even if cached name exists");
        check(p.next(picker("Other Server"), 1000).kind == 0 && p.stopped(), "wrong server cannot select even a matching user name");
        p = policy();
        check(p.next(frame().button(40, "Account A"), 0).kind == 0 && !p.stopped(), "loading screen never selects matching media text");
        check(p.actionCount() == 0, "waiting for a header consumes no UI actions");
        check(p.next(frame(), 7999).kind == 0 && !p.stopped(), "brief WebView loading remains within the bounded wait");
        check(p.next(frame(), 8000).kind == 0 && p.stopped(), "unknown screen stops after the short control-loading grace");
        p = policy();
        check(p.next(frame(), 0).kind == 0, "empty initial accessibility tree waits");
        click(p, home(), 2500, 1, "header arriving later still opens account menu");
        click(p, menu(true), 3500, 2, "loading must not advance the UI state before a click");
        EmbyAccountPolicy.Frame partialPicker = frame().text("Sign in to My Server")
                .button(4, "Manual Login").button(5, "Forgot Password?");
        check(p.next(partialPicker, 4500).kind == 0 && !p.stopped(), "verified picker can expose header controls later");
        click(p, picker("My Server"), 5500, 1, "loaded picker header opens saved accounts once");
        click(p, menu(true), 6500, 30, "delayed headers still reach the saved account");
        check(p.actionCount() == 4, "waiting does not add synthetic clicks or change the action budget");
        p = policy(); EmbyAccountPolicy.Frame other = home(); other.owned = false;
        check(p.next(other, 0).kind == 0 && p.state().equals("left-emby"), "foreign applications are out of scope");

        p = policy(); click(p, picker("My Server"), 0, 1, "beginning on correct login picker is supported");
        check(p.next(menu(true).menuButton(31, "Account A"), 1000).kind == 0 && p.state().equals("ambiguous-account"), "duplicate user buttons fail closed");
        p = policy(); check(p.next(home().button(90, "Settings"), 0).kind == 0 && p.stopped(), "duplicate navigation controls fail closed");
        p = policy(); p.cancel("user-interrupted"); check(p.next(home(), 0).kind == 0, "explicit user cancellation stops control");
        p = policy(); check(p.next(home(), 60000).kind == 0 && p.state().equals("expired"), "bounded timeout prevents late surprise actions");
        p = new EmbyAccountPolicy(new EmbyAccountPolicy.Labels(), "Sign Out", "My Server", 0);
        check(p.next(menu(true), 0).kind == 0 && p.stopped(), "control-label user names require manual selection");
        p = new EmbyAccountPolicy(new EmbyAccountPolicy.Labels(), "", "My Server", 0);
        check(p.stopped(), "empty user rejected");
        p = new EmbyAccountPolicy(new EmbyAccountPolicy.Labels(), "Account A", null, 0);
        check(p.stopped(), "missing server rejected without a crash");

        EmbyAccountPolicy.Labels zh = new EmbyAccountPolicy.Labels();
        p = new EmbyAccountPolicy(zh, "User A", "服务器", 0);
        EmbyAccountPolicy.Frame zhMenu = frame().menuButton(2, "\ue7fd 更改用户").menuButton(3, "\ue879 注销退出").button(1, "设置");
        click(p, zhMenu, 0, 2, "Chinese controls with Emby icon glyphs are recognized");
        EmbyAccountPolicy.Frame zhPicker = frame().text("登录 服务器").button(1, "设置").button(4, "\ue898 手动登录").button(5, "\ue887 忘记密码？").button(40, " User A");
        click(p, zhPicker, 1000, 1, "Chinese server heading verifies correctly");
        click(p, zhMenu.menuButton(30, "\ue7fd User A"), 2000, 30, "saved account keeps exact user spelling");
        check(EmbyAccountPolicy.name("\ue7fd  User\u00a0A ").equals("User A"), "icon and nonbreaking-space normalization");
        check(!EmbyAccountPolicy.name("User A").equals(EmbyAccountPolicy.name("user a")), "user identity remains case-sensitive");
        System.out.println("PASS: " + checks + " account UI assertions; no credentials, network or TV used.");
    }
}
