package io.freemytv;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** Only the two official Emby clients can receive a home-poster handoff. */
final class EmbyNativePlayer {
    static final String TV = "tv.emby.embyatv", ANDROID = "com.mb.android";
    private EmbyNativePlayer() { }
    static boolean supported(String pkg) { return TV.equals(pkg) || ANDROID.equals(pkg); }
    static boolean installed(Context c, String pkg) {
        return supported(pkg) && HomeApps.launchIntent(c.getPackageManager(), pkg) != null;
    }
    static String preferred(Context c) {
        String selected = Prefs.embyPlayer();
        if (installed(c, selected)) return selected;
        if (installed(c, TV)) return TV;
        return installed(c, ANDROID) ? ANDROID : "";
    }
    static String label(String pkg) { return TV.equals(pkg) ? "Emby TV" : ANDROID.equals(pkg) ? "Emby Android" : "—"; }
    static String other(String player) { return TV.equals(player) ? ANDROID : TV; }
    static boolean hasOtherEnabled(Context c, String player) {
        String other = other(player);
        try { return c.getPackageManager().getApplicationInfo(other, 0).enabled; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { return false; }
    }
    static String version(Context c, String pkg) {
        if (!supported(pkg)) return "";
        try { return c.getPackageManager().getPackageInfo(pkg, 0).versionName; }
        catch (Exception e) { return ""; }
    }
    static List<String> installed(Context c) {
        List<String> result = new ArrayList<>();
        if (installed(c, TV)) result.add(TV);
        if (installed(c, ANDROID)) result.add(ANDROID);
        return result;
    }
    static List<String> shelf(Context c, List<String> original) {
        List<String> result = new ArrayList<>();
        String player = preferred(c);
        for (String pkg : original) {
            String chosen = supported(pkg) && !player.isEmpty() ? player : pkg;
            if (!result.contains(chosen)) result.add(chosen);
        }
        return result;
    }
}
