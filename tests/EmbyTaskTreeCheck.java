package io.freemytv;

import java.io.IOException;

public final class EmbyTaskTreeCheck {
    private static int checks;
    private static void check(boolean okay, String reason) {
        checks++;
        if (!okay) throw new AssertionError(reason);
    }
    private static String task(int id) { return "        #2 Task=" + id + " type=standard mode=fullscreen\n"; }
    private static String activity(String hash, int user, String component, int id) {
        return "         #0 ActivityRecord{" + hash + " u" + user + " " + component + " t" + id
                + "} type=standard mode=fullscreen\n";
    }
    private static String nativeActivity(String hash, String cls, int id) {
        return activity(hash, 0, "tv.emby.embyatv/" + cls, id);
    }
    private static void refuses(String dump, String reason) throws Exception {
        try { EmbyTaskTree.find(dump, 0); throw new AssertionError(reason); }
        catch (IOException expected) {
            check(!expected.getMessage().contains("private.other"), "failure does not include unrelated app identity");
        }
    }
    private static String repeat(char value, int count) {
        char[] out = new char[count]; java.util.Arrays.fill(out, value); return new String(out);
    }
    public static void main(String[] args) throws Exception {
        String main = task(590) + nativeActivity("887f18b", ".browsing.MainActivity", 590)
                + "          #0 c7fc4b9 tv.emby.embyatv/tv.emby.embyatv.browsing.MainActivity type=standard\n";
        EmbyTaskTree.Task picked = EmbyTaskTree.find(main, 0);
        check(picked.id == 590 && picked.activities == 1 && picked.playbackPages == 0,
                "Hisense container and duplicate window row identify one native activity");
        check(picked.topActivity.equals("tv.emby.embyatv.browsing.MainActivity"), "short class expands canonically");
        String playback = task(590)
                + nativeActivity("a1", ".playback.PlaybackOverlayActivity", 590).replace("#0", "#2")
                + nativeActivity("a2", "tv.emby.embyatv.playback.PlaybackOverlayActivity", 590).replace("#0", "#1")
                + nativeActivity("a3", ".browsing.MainActivity", 590);
        picked = EmbyTaskTree.find(playback, 0);
        check(picked.playbackPages == 2 && picked.activities == 3
                && picked.topActivity.endsWith(".PlaybackOverlayActivity"), "top-first pages are counted separately");
        String nestedHome = "       #1 Task=1 type=home\n        #0 Task=583 type=home\n"
                + activity("b1", 0, "io.freemytv/.HomeActivity", 583);
        picked = EmbyTaskTree.find(nestedHome + main, 0);
        check(picked.id == 590 && picked.activities == 1, "nested home task does not capture sibling Emby task");
        check(EmbyTaskTree.find(nestedHome, 0) == null, "unrelated root activity is not a native candidate");
        check(EmbyTaskTree.find("", 0) == null, "empty hierarchy has no warm task");
        check(EmbyTaskTree.find(task(590) + activity("a1", 10, "tv.emby.embyatv/.browsing.MainActivity", 590), 0) == null,
                "other Android user is excluded");
        check(EmbyTaskTree.find(task(590) + activity("a1", 10, "tv.emby.embyatv/.browsing.MainActivity", 590), 10).id == 590,
                "requested Android user can be selected");
        check(EmbyTaskTree.find(task(590) + activity("a1", 0, "tv.emby.embyatv.evil/.MainActivity", 590), 0) == null,
                "near-prefix package is excluded");
        check(EmbyTaskTree.find(task(590) + "          #0 887f18b tv.emby.embyatv/tv.emby.embyatv.browsing.MainActivity type=standard\n", 0) == null,
                "window text alone cannot create a task identity");
        check(EmbyTaskTree.find(nativeActivity("a1", ".browsing.MainActivity", 590), 0).id == 590,
                "true activity task ID works when task heading is absent");
        refuses(main + task(591) + nativeActivity("a2", ".browsing.MainActivity", 591), "ambiguous native tasks");
        refuses(task(590) + activity("a2", 0, "private.other/.MainActivity", 590)
                + nativeActivity("a1", ".browsing.MainActivity", 590), "foreign activity before native");
        refuses(main + activity("a2", 0, "private.other/.MainActivity", 590), "foreign activity after native");
        refuses(main + activity("a2", 0, "tv.emby.embyatv.evil/.MainActivity", 590), "spoof package in native task");
        refuses(main + activity("a2", 10, "tv.emby.embyatv/.browsing.MainActivity", 590), "mixed users");
        refuses(task(590) + nativeActivity("a1", ".browsing.MainActivity", 591), "nearest heading must agree with real ID");
        refuses("       #1 Task=1 type=home\n" + nativeActivity("a1", ".browsing.MainActivity", 590), "ancestor task cannot be mistaken for leaf");
        refuses(main + nativeActivity("887f18b", ".browsing.MainActivity", 590), "repeated activity identity");
        refuses(main + task(590) + nativeActivity("a2", ".browsing.MainActivity", 590), "repeated task branch");
        refuses(task(590) + nativeActivity("a1", "private.other.MainActivity", 590), "foreign class namespace");
        refuses(main.replace("t590}", "t2147483648}"), "task ID overflow");
        refuses(main.replace("u0 ", "u2147483648 "), "user ID overflow");
        refuses(main.replace("Task=590", "Task=590x"), "malformed task ID");
        refuses(main.replace("t590}", "t-1}"), "negative task ID");
        refuses(main.replace("t590}", "}"), "missing true task ID");
        refuses(main.replace("887f18b", repeat('a', 65)), "oversize record identity");
        refuses(main.replace(".browsing.MainActivity", "." + repeat('a', 513)), "oversize component identity");
        refuses(main.replace("#2", "#999999999999"), "oversize row index");
        refuses(main.replace("t590}", "t590 f}"), "finishing native page cannot resume");
        refuses(main.replace("t590}", "t590 f}}"), "AOSP finishing string cannot resume");
        check(EmbyTaskTree.find(task(591) + activity("b2", 0, "private.other/.MainActivity", 591)
                .replace("t591}", "t591 f}}") + main, 0).id == 590, "unrelated finishing record does not hide native task");
        refuses(main + activity("a2", 0, "private.other/.MainActivity", 590).replace("#0 ", "#0   "),
                "extra row spaces do not hide foreign activity");
        refuses("Permission Denial: cannot dump window\n", "permission error is not a missing task");
        refuses(main + "\u0000", "NUL cannot truncate inspection");
        refuses(main.replace("        #2", "\t#2"), "ambiguous tabs are refused");
        refuses(main.replace("ActivityRecord{", "ActivityRecord {"), "malformed record syntax is refused");
        refuses(repeat('x', 8193), "oversize line");
        refuses(repeat('x', 2 * 1024 * 1024 + 1), "oversize dump");
        refuses(null, "null dump");
        try { EmbyTaskTree.find(main, -1); throw new AssertionError("negative user"); }
        catch (IOException expected) { checks++; }
        System.out.println("PASS: " + checks + " native task identity, mixed-task and bounded-input assertions.");
    }
}
