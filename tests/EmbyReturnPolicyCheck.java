package io.freemytv;

/** Pure policy checks: real Back timing, native HUD behavior and one return navigation. */
public final class EmbyReturnPolicyCheck {
    private static int checks;
    private static void check(boolean okay, String reason) {
        checks++;
        if (!okay) throw new AssertionError(reason);
    }
    private static EmbyReturnPolicy armed(long at) {
        EmbyReturnPolicy policy = new EmbyReturnPolicy();
        check(policy.back(at, at), "a fresh physical Back arms return observation");
        return policy;
    }

    private static void eventTiming() {
        for (long lag : new long[]{0, 100, 1499, 1500}) {
            EmbyReturnPolicy policy = new EmbyReturnPolicy();
            check(policy.back(10000 - lag, 10000) && policy.waiting(), "fresh event accepted at lag " + lag);
            check(policy.show("details", 10010), "accepted event permits native details return at lag " + lag);
        }
        for (long event : new long[]{-1, 0, 8499, 10001, Long.MAX_VALUE}) {
            EmbyReturnPolicy policy = new EmbyReturnPolicy();
            check(!policy.back(event, 10000) && !policy.waiting(), "stale, negative or future event cannot arm: " + event);
            check(!policy.show("home", 10100), "rejected event never produces navigation: " + event);
        }
        EmbyReturnPolicy policy = armed(10000);
        check(!policy.back(8499, 10000), "a stale event cannot replace the fresh Back");
        check(!policy.back(10001, 10000), "a future event cannot replace the fresh Back");
        check(policy.waiting() && policy.show("home", 10100), "rejected later input preserves the valid pending return");
    }

    private static void nativeHudAndTimeout() {
        EmbyReturnPolicy policy = armed(10000);
        for (long now : new long[]{10100, 11000, 14999, 15000}) {
            check(!policy.show("player", now), "Back that only hides HUD never navigates at " + now);
            check(policy.waiting(), "player observation remains bounded but armed at " + now);
        }
        check(!policy.show("player", 15001) && !policy.waiting(), "HUD-only Back times out after five seconds");
        check(!policy.show("details", 15002), "late player exit does not navigate after timeout");
        check(policy.back(16000, 16000) && policy.waiting(), "a second physical Back can re-arm after HUD timeout");
        check(policy.show("details", 16100) && !policy.waiting(), "second Back leaving player can navigate once");
        check(!policy.back(16200, 16200) && !policy.show("home", 16300), "completed return cannot be re-armed");

        policy = armed(20000);
        check(!policy.show("loading", 22000) && policy.waiting(), "transient missing native tree waits");
        check(!policy.show(null, 23000) && policy.waiting(), "unknown native screen cannot trigger a return");
        check(!policy.show("loading", 25001) && !policy.waiting(), "loading also obeys the deadline");
        check(policy.back(26000, 26000), "transient loading timeout does not permanently disable later Back");
        check(policy.show("home", 31000), "return at the exact five-second boundary is allowed");

        policy = armed(40000);
        check(!policy.show("player", 44000), "first key may leave the HUD/player active");
        check(policy.back(44500, 44500), "a distinct fresh Back while waiting renews its own observation window");
        check(policy.show("details", 45100), "later Back can finish after the original key deadline");
    }

    private static void oneNavigation() {
        for (String exit : new String[]{"home", "details"}) {
            EmbyReturnPolicy policy = new EmbyReturnPolicy();
            check(!policy.waiting() && !policy.show(exit, 1000), "native screen alone never triggers return: " + exit);
            check(policy.back(2000, 2000), "Back arms an owned player exit: " + exit);
            int navigation = 0;
            for (String screen : new String[]{"player", "loading", exit, exit, "home", "details"})
                if (policy.show(screen, 2100)) navigation++;
            check(navigation == 1 && !policy.waiting(), "native exit navigates exactly once: " + exit);
            check(!policy.back(2200, 2200) && !policy.show("details", 2300), "later Back belongs entirely to native app: " + exit);
        }
    }

    private static void cancellation() {
        for (String screen : new String[]{"auth", "other", "unavailable"}) {
            EmbyReturnPolicy policy = armed(10000);
            check(!policy.show(screen, 10100) && !policy.waiting(), "unsafe or disconnected screen cancels return: " + screen);
            check(!policy.show("home", 10200) && !policy.show("details", 10300), "cancelled return never reappears: " + screen);
            check(!policy.back(10400, 10400), "new Back cannot revive cancelled ownership: " + screen);
        }
        EmbyReturnPolicy policy = armed(10000);
        policy.cancel(); // HomeActivity explicitly ends ownership when Home resumes.
        check(!policy.waiting(), "explicit Home clears an armed return");
        check(!policy.show("home", 10100) && !policy.show("details", 10200), "Home cancellation prevents delayed navigation");
        check(!policy.back(10300, 10300), "Home cancellation prevents re-arm");
        policy.cancel();
        check(!policy.waiting() && !policy.show("details", 10400), "repeated cancellation is harmless");
        policy = new EmbyReturnPolicy();
        policy.cancel();
        check(!policy.back(10000, 10000), "Home before any Back also ends return ownership");
    }

    public static void main(String[] args) {
        eventTiming();
        nativeHudAndTimeout();
        oneNavigation();
        cancellation();
        System.out.println("PASS: " + checks + " Back timing, HUD timeout, one-return and cancellation assertions.");
    }
}
