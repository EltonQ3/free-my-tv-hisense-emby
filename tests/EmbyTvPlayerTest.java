package io.freemytv;

import java.util.*;
import org.json.*;

public final class EmbyTvPlayerTest {
    static int checks;
    static final long NOW = 1792930000000L;
    static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    static JSONObject session(String id, String device, String client, String ip) {
        return new JSONObject().put("Id", id).put("DeviceId", device).put("Client", client)
                .put("UserId", "selected-user").put("RemoteEndPoint", ip)
                .put("LastActivityDate", java.time.Instant.ofEpochMilli(NOW).toString())
                .put("SupportsRemoteControl", true).put("PlayableMediaTypes", new JSONArray().put("Video"));
    }
    static EmbyPlayerSession.Session pick(JSONArray all, String pin, String pkg) throws Exception {
        return EmbyPlayerSession.select(all, new HashSet<>(Arrays.asList("192.168.1.20")), pin, NOW, pkg);
    }
    static EmbyPlayerSession.Session tv25(JSONArray all) throws Exception {
        return EmbyPlayerSession.select(all, new HashSet<>(Arrays.asList("192.168.1.20")), "", NOW,
                "tv.emby.embyatv", "2.5.03g");
    }
    static EmbyPlayerSession.Session tv25(JSONArray all, boolean foreground) throws Exception {
        return EmbyPlayerSession.select(all, new HashSet<>(Arrays.asList("192.168.1.20")), "", NOW,
                "tv.emby.embyatv", "2.5.03g", foreground);
    }
    static JSONObject at(JSONObject source, long millis) {
        return new JSONObject(source.toString()).put("LastActivityDate", java.time.Instant.ofEpochMilli(millis).toString());
    }
    static void notReady(EmbyPlayerSession.Session selected, String reason) {
        check(selected == null || !selected.ready, reason);
    }
    static void foregroundChecks(JSONObject anchor, JSONObject control) throws Exception {
        JSONObject idleProfile = at(anchor, NOW - 86400000L);
        JSONObject idleController = at(control, NOW - 86400000L).put("UserId", "startup-user");
        JSONArray idle = new JSONArray().put(idleProfile).put(idleController);
        check(tv25(idle) == null && tv25(idle, false) == null, "legacy API and explicit false reject idle native profile");
        EmbyPlayerSession.Session selected = tv25(idle, true);
        check(selected != null && selected.ready && selected.id.equals("tv-control")
                && selected.device.equals("shared-device") && selected.user.equals("selected-user")
                && selected.controllerUser.equals("startup-user"), "verified foreground binds unique idle profile and ready same-device controller");
        check(EmbyPlayerSession.playBody(selected, "selected-user", "episode-1", 1443000000L)
                .getLong("StartPositionTicks") == 1443000000L, "foreground timestamp exception preserves exact native-user resume request");
        try { EmbyPlayerSession.playBody(selected, "startup-user", "episode-1", 1443000000L); throw new AssertionError("controller user playback"); }
        catch (java.io.IOException expected) { checks++; }
        notReady(tv25(new JSONArray().put(idleProfile), true), "foreground alone does not make browsing-only profile ready");
        check(tv25(new JSONArray().put(idleController), true) == null, "foreground cannot substitute controller for missing native profile");
        try { tv25(new JSONArray(idle.toString()).put(new JSONObject(idleProfile.toString()).put("Id", "second-profile")), true); throw new AssertionError("ambiguous idle profile"); }
        catch (java.io.IOException expected) { checks++; }
        try { tv25(new JSONArray(idle.toString()).put(new JSONObject(idleController.toString()).put("Id", "second-control")), true); throw new AssertionError("ambiguous idle controller"); }
        catch (java.io.IOException expected) { checks++; }

        String future = java.time.Instant.ofEpochMilli(NOW + 60001L).toString();
        for (String invalidDate : new String[]{"not-a-date", "1970-01-01T00:00:00Z", "1969-12-31T23:59:59Z", future}) {
            check(tv25(new JSONArray().put(new JSONObject(idleProfile.toString()).put("LastActivityDate", invalidDate))
                    .put(idleController), true) == null, "invalid, zero, negative or excessively future native timestamp is rejected: " + invalidDate);
            notReady(tv25(new JSONArray().put(idleProfile)
                    .put(new JSONObject(idleController.toString()).put("LastActivityDate", invalidDate)), true),
                    "invalid, zero, negative or excessively future controller timestamp is rejected: " + invalidDate);
        }
        JSONObject missingDate = new JSONObject(idleController.toString()); missingDate.remove("LastActivityDate");
        notReady(tv25(new JSONArray().put(idleProfile).put(missingDate), true), "missing controller timestamp is rejected");
        missingDate = new JSONObject(idleProfile.toString()); missingDate.remove("LastActivityDate");
        check(tv25(new JSONArray().put(missingDate).put(idleController), true) == null, "missing native timestamp is rejected");
        for (String field : new String[]{"DeviceId", "RemoteEndPoint", "ApplicationVersion"}) {
            JSONObject altered = new JSONObject(idleController.toString()).put(field,
                    field.equals("RemoteEndPoint") ? "192.168.1.99" : "other");
            notReady(tv25(new JSONArray().put(idleProfile).put(altered), true), "foreground preserves paired controller identity: " + field);
            altered = new JSONObject(idleProfile.toString()).put(field,
                    field.equals("RemoteEndPoint") ? "192.168.1.99" : "other");
            notReady(tv25(new JSONArray().put(altered).put(idleController), true), "foreground preserves native profile identity: " + field);
        }
        notReady(tv25(new JSONArray().put(idleProfile).put(new JSONObject(idleController.toString())
                .put("SupportsRemoteControl", false)), true), "foreground never grants unsupported remote control");
        notReady(tv25(new JSONArray().put(idleProfile).put(new JSONObject(idleController.toString())
                .put("PlayableMediaTypes", new JSONArray().put("Audio"))), true), "foreground never treats audio-only controller as video-ready");
        notReady(tv25(new JSONArray().put(idleProfile).put(new JSONObject(idleController.toString())
                .put("Id", idleProfile.getString("Id"))), true), "profile session cannot serve as its own paired controller");
        check(EmbyPlayerSession.select(idle, new HashSet<>(Arrays.asList("192.168.1.20")), "other-device", NOW,
                "tv.emby.embyatv", "2.5.03g", true) == null, "foreground cannot override pinned device");
        check(EmbyPlayerSession.select(idle, new HashSet<>(Arrays.asList("192.168.1.20")), "", NOW,
                "tv.emby.embyatv", "", true) == null, "foreground needs verified installed TV version");

        for (boolean ready : new boolean[]{false, true}) {
            JSONObject universal = session("mobile-only", "mobile-device", "Android", "192.168.1.20")
                    .put("ApplicationVersion", "3.5.35").put("SupportsRemoteControl", ready);
            for (String date : new String[]{java.time.Instant.ofEpochMilli(NOW).toString(),
                    java.time.Instant.ofEpochMilli(NOW - 86400000L).toString(), "not-a-date", future}) {
                JSONArray all = new JSONArray().put(new JSONObject(universal.toString()).put("LastActivityDate", date));
                EmbyPlayerSession.Session strict = EmbyPlayerSession.select(all,
                        new HashSet<>(Arrays.asList("192.168.1.20")), "", NOW, "com.mb.android", "3.5.35", false);
                EmbyPlayerSession.Session verified = EmbyPlayerSession.select(all,
                        new HashSet<>(Arrays.asList("192.168.1.20")), "", NOW, "com.mb.android", "3.5.35", true);
                check(strict == null ? verified == null : verified != null && strict.id.equals(verified.id)
                        && strict.ready == verified.ready && strict.user.equals(verified.user),
                        "foreground flag leaves universal Android behavior unchanged for ready=" + ready + " date=" + date);
            }
        }
        JSONObject universal = session("mobile-only", "mobile-device", "Android", "192.168.1.20")
                .put("ApplicationVersion", "3.5.35");
        for (boolean foreground : new boolean[]{false, true}) {
            check(EmbyPlayerSession.select(new JSONArray().put(universal),
                    new HashSet<>(Arrays.asList("192.168.1.20")), "other-device", NOW,
                    "com.mb.android", "3.5.35", foreground) == null, "universal pin guard survives foreground=" + foreground);
            check(EmbyPlayerSession.select(new JSONArray().put(universal),
                    new HashSet<>(Arrays.asList("192.168.1.99")), "", NOW,
                    "com.mb.android", "3.5.35", foreground) == null, "universal local-address guard survives foreground=" + foreground);
            check(EmbyPlayerSession.select(new JSONArray().put(universal),
                    new HashSet<>(Arrays.asList("192.168.1.20")), "", NOW,
                    "com.mb.android", "different-version", foreground) == null, "universal version guard survives foreground=" + foreground);
        }
    }
    static EmbyAccountPolicy.Frame home() {
        EmbyAccountPolicy.Frame f = new EmbyAccountPolicy.Frame(); f.owned = true; f.accountMenu = 7; return f;
    }
    static EmbyAccountPolicy.Frame menu(String label) {
        EmbyAccountPolicy.Frame f = new EmbyAccountPolicy.Frame(); f.owned = true; f.tvMenu = true;
        return f.button(1, "注销退出").button(2, "切换服务器").button(3, label);
    }
    public static void main(String[] args) throws Exception {
        JSONObject android = session("android-session", "mobile-device", "Android", "192.168.1.20:5551");
        JSONObject tv = session("tv-session", "tv-device", "AndroidTv", "192.168.1.20:5552");
        JSONArray both = new JSONArray().put(android).put(tv);
        check(pick(both, "", "tv.emby.embyatv").id.equals("tv-session"), "coexisting clients: TV default selects TV only");
        check(pick(both, "", "com.mb.android").id.equals("android-session"), "rollback selects Android only");
        check(pick(both, "mobile-device", "tv.emby.embyatv") == null, "old mobile pin cannot redirect TV playback");
        check(pick(new JSONArray().put(android), "", "tv.emby.embyatv") == null, "TV unavailable never dispatches to mobile");
        check(pick(new JSONArray().put(new JSONObject(tv.toString()).put("RemoteEndPoint", "192.168.1.99")), "", "tv.emby.embyatv") == null, "same user on another TV is excluded");
        for (String client : new String[]{"Android TV", "AndroidTv", "Emby for Android TV"})
            check(pick(new JSONArray().put(new JSONObject(tv.toString()).put("Client", client)), "", "tv.emby.embyatv") != null, "official TV client name accepted");
        try { pick(new JSONArray().put(tv).put(new JSONObject(tv.toString()).put("Id", "duplicate")), "", "tv.emby.embyatv"); throw new AssertionError("ambiguous TV"); }
        catch (java.io.IOException expected) { checks++; }
        check(EmbyPlayerSession.playBody(pick(both, "", "tv.emby.embyatv"), "selected-user", "episode-1", 3069550000L)
                .getLong("StartPositionTicks") == 3069550000L, "TV remote play retains exact resume ticks");

        JSONObject anchor = session("tv-browse", "shared-device", "AndroidTv", "192.168.1.20")
                .put("ApplicationVersion", "2.5.03g").put("SupportsRemoteControl", false);
        JSONObject control = session("tv-control", "shared-device", "Emby for Android", "192.168.1.20")
                .put("ApplicationVersion", "2.5.03g");
        check(tv25(new JSONArray().put(anchor).put(control)).id.equals("tv-control"), "TV 2.5 paired controller identified");
        check(!tv25(new JSONArray().put(anchor)).ready, "browsing-only session never receives play");
        check(tv25(new JSONArray().put(control)) == null, "Android alias alone is insufficient");
        for (String field : new String[]{"DeviceId", "RemoteEndPoint", "ApplicationVersion"}) {
            JSONObject altered = new JSONObject(control.toString()).put(field, "other");
            check(!tv25(new JSONArray().put(anchor).put(altered)).ready, "controller exact match required: " + field);
        }
        EmbyPlayerSession.Session switched = tv25(new JSONArray().put(anchor).put(new JSONObject(control.toString()).put("UserId", "startup-user")));
        check(switched.user.equals("selected-user") && switched.controllerUser.equals("startup-user"), "verified native TV profile governs playback after account switch");
        check(EmbyPlayerSession.playBody(switched, "selected-user", "episode-1", 1443000000L).getString("ControllingUserId").equals("selected-user"), "control transport never changes the selected playback user");
        JSONObject old = new JSONObject(control.toString()).put("LastActivityDate", java.time.Instant.ofEpochMilli(NOW - 91000).toString());
        check(!tv25(new JSONArray().put(anchor).put(old)).ready, "stale Android controller is excluded");
        old = new JSONObject(anchor.toString()).put("LastActivityDate", java.time.Instant.ofEpochMilli(NOW - 91000).toString());
        check(tv25(new JSONArray().put(old).put(control)) == null, "stale TV anchor cannot bind controller");
        try { tv25(new JSONArray().put(anchor).put(control).put(new JSONObject(control.toString()).put("Id", "duplicate-control"))); throw new AssertionError("ambiguous controller"); }
        catch (java.io.IOException expected) { checks++; }
        check(!pick(new JSONArray().put(anchor).put(control), "", "tv.emby.embyatv").ready,
                "without installed version no client alias is accepted");
        check(EmbyPlayerSession.select(new JSONArray().put(anchor).put(control), new HashSet<>(Arrays.asList("192.168.1.20")), "", NOW,
                "com.mb.android", "3.5.35") == null, "TV controller cannot masquerade as installed universal version");
        foregroundChecks(anchor, control);

        EmbyTvAccountPolicy p = new EmbyTvAccountPolicy("ym", true, 0);
        check(p.next(home(), 0).id == 7, "opens only the native account control");
        check(p.next(menu("切换到 ym"), 1000).id == 3, "selects the exact visible target account");
        check(p.next(menu("切换到 ym"), 3000).kind == 0, "after selection waits for server session, no repeated switch");
        check(p.actionCount() == 2, "native path needs two bounded UI actions");
        p = new EmbyTvAccountPolicy("ym", false, 0);
        check(p.next(menu("切换到 ym"), 0).kind == 0 && p.stopped(), "unverified server does not act");
        p = new EmbyTvAccountPolicy("ym", true, 0);
        EmbyAccountPolicy.Frame wrong = home(); wrong.owned = false;
        check(p.next(wrong, 0).kind == 0 && p.stopped(), "never acts in another app");
        p = new EmbyTvAccountPolicy("ym", true, 0);
        check(p.next(menu("切换到 ym-other"), 0).kind == 0 && p.state().equals("account-not-visible"), "partial user name never matches");
        p = new EmbyTvAccountPolicy("ym", true, 0);
        check(p.next(menu("切换到 YM"), 0).kind == 0, "account case is exact");
        p = new EmbyTvAccountPolicy("ym", true, 0);
        check(p.next(menu("切换到 ym").button(4,"切换到 ym"), 0).kind == 0 && p.state().equals("ambiguous-account"), "duplicate account controls stop");
        for (String label : new String[]{"Switch to ym", "切换到 ym", "切換到 ym"}) {
            p = new EmbyTvAccountPolicy("ym", true, 0);
            check(p.next(menu(label), 0).id == 3, "native menu localization supported");
        }
        p = new EmbyTvAccountPolicy("ym", true, 0);
        EmbyAccountPolicy.Frame auth = menu("切换到 ym"); auth.editable = true;
        check(p.next(auth, 0).kind == 0 && p.state().equals("auth-required"), "password/PIN stays manual");
        p = new EmbyTvAccountPolicy("ym", true, 0);
        check(p.next(menu("切换到 ym"), 60000).kind == 0 && p.stopped(), "expired request cannot switch later");
        p = new EmbyTvAccountPolicy("ym", true, 0); p.cancel("user-interrupted");
        check(p.next(menu("切换到 ym"), 0).kind == 0, "interrupted user request never resumes");
        p = new EmbyTvAccountPolicy("注销退出", true, 0);
        check(p.next(menu("切换到 注销退出"), 0).id == 3, "a control-like user name cannot click the real sign-out action");
        System.out.println("PASS: " + checks + " TV-client isolation, resume and native-account assertions.");
    }
}
