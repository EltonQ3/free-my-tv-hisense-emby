package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit poster handoff: one PlayNow on a verified official player session. */
final class EmbyPlayer {
    static volatile String lastStatus = "{}";
    private static final String PIN = "emby-player";

    static Set<String> localAddresses() throws Exception {
        Set<String> result = new HashSet<>();
        for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!n.isUp() || n.isLoopback()) continue;
            for (InetAddress ip : Collections.list(n.getInetAddresses()))
                if (!ip.isLoopbackAddress()) result.add(EmbyPlayerSession.ip(ip.getHostAddress()));
        }
        result.remove("");
        return result;
    }

    static JSONArray sessions(JSONObject config) throws Exception {
        return new JSONArray(EmbyRecs.request(config, "/Sessions"));
    }

    static EmbyPlayerSession.Session session(Context context, JSONObject config, JSONArray list) throws Exception {
        return session(context, config, list, EmbyNativePlayer.preferred(context));
    }

    private static String pinName(String player) { return EmbyNativePlayer.TV.equals(player) ? PIN + "-tv" : PIN; }

    static EmbyPlayerSession.Session session(Context context, JSONObject config, JSONArray list, String player) throws Exception {
        return session(context, config, list, player, false);
    }

    static EmbyPlayerSession.Session session(Context context, JSONObject config, JSONArray list, String player,
            boolean verifiedNativeForeground) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(pinName(player), Context.MODE_PRIVATE);
        String pinned = config.getString("server_id").equals(prefs.getString("server", ""))
                ? prefs.getString("device", "") : "";
        boolean other = EmbyNativePlayer.hasOtherEnabled(context, player);
        return EmbyPlayerSession.select(list, localAddresses(), pinned, System.currentTimeMillis(), player,
                EmbyNativePlayer.version(context, player), verifiedNativeForeground, other,
                other ? EmbyNativePlayer.version(context, EmbyNativePlayer.other(player)) : "");
    }

    static boolean current(Context context, EmbyPlayback.Target target) {
        try { return target.config != null && EmbyPlayerSession.sameConnection(target.config, EmbyRecs.readConfig(context)); }
        catch (Exception e) { return false; }
    }

    static void pin(Context context, EmbyPlayback.Target target, EmbyPlayerSession.Session session) {
        context.getSharedPreferences(pinName(EmbyNativePlayer.preferred(context)), Context.MODE_PRIVATE).edit()
                .putString("server", target.serverId).putString("device", session.device).apply();
    }

    /** Native details only, without another play command or startup activity. */
    static JSONObject browseItemTv(Context context, EmbyPlayback.Target target, EmbyPlayerSession.Session session) throws Exception {
        return browseItemTv(context, target, session, () -> true);
    }

    static JSONObject browseItemTv(Context context, EmbyPlayback.Target target, EmbyPlayerSession.Session session,
            java.util.concurrent.Callable<Boolean> foreground) throws Exception {
        requirePlayer(context, session);
        if (!EmbyNativePlayer.TV.equals(EmbyNativePlayer.preferred(context))
                || !current(context, target) || session == null || !session.ready || !target.userId.equals(session.user)
                || !session.id.matches("[A-Za-z0-9-]{1,200}")) throw new IOException("TV connection changed");
        JSONObject detail = new JSONObject(EmbyRecs.request(target.config, "/Users/" + target.userId + "/Items/" + target.episode.id
                + "?EnableUserData=true&EnableImages=false"));
        if (!target.episode.id.equals(detail.optString("Id")) || detail.optString("Name").trim().isEmpty()
                || !("Movie".equals(detail.optString("Type")) || "Episode".equals(detail.optString("Type"))))
            throw new IOException("Invalid native item");
        if (!current(context, target) || !foreground.call()) throw new IOException("Native foreground changed");
        EmbyHttp.post(target.config.getString("server_url").replaceAll("/+$", "") + "/Sessions/" + session.id
                + "/Viewing?ItemId=" + target.episode.id + "&ItemType=" + detail.getString("Type"),
                target.config.getString("api_key"), new byte[0], 16384);
        return detail;
    }

    private static final java.util.concurrent.atomic.AtomicInteger DISPATCHES = new java.util.concurrent.atomic.AtomicInteger();
    static int dispatchCount() { return DISPATCHES.get(); }
    static void stopTv(Context context, EmbyPlayback.Target target, EmbyPlayerSession.Session session) throws Exception {
        requirePlayer(context, session);
        if (!EmbyNativePlayer.TV.equals(EmbyNativePlayer.preferred(context))
                || !current(context, target) || session == null || !session.ready || !session.id.matches("[A-Za-z0-9-]{1,200}"))
            throw new IOException("TV connection changed");
        EmbyHttp.post(target.config.getString("server_url").replaceAll("/+$", "") + "/Sessions/" + session.id + "/Playing/Stop",
                target.config.getString("api_key"), new byte[0], 16384);
    }

    private static void requirePlayer(Context context, EmbyPlayerSession.Session session) throws Exception {
        String player = EmbyNativePlayer.preferred(context);
        boolean other = EmbyNativePlayer.hasOtherEnabled(context, player);
        EmbyPlayerSession.requirePlayer(session, player, EmbyNativePlayer.version(context, player), other,
                other ? EmbyNativePlayer.version(context, EmbyNativePlayer.other(player)) : "");
    }

    /** One explicit dispatch, never a retry; TV account was verified through its own native profile. */
    static void play(Context context, EmbyPlayback.Target target, EmbyPlayerSession.Session session) throws Exception {
        String player = EmbyNativePlayer.preferred(context);
        requirePlayer(context, session);
        if (!current(context, target)) throw new IOException("Connection changed");
        String path = EmbyPlayerSession.playPath(session, target.userId, target.episode.id, target.episode.position);
        JSONObject body = EmbyNativePlayer.TV.equals(player) ? new JSONObject().put("ControllingUserId", target.userId)
                : EmbyPlayerSession.playBody(session, target.userId, target.episode.id, target.episode.position);
        DISPATCHES.incrementAndGet();
        EmbyHttp.post(target.config.getString("server_url").replaceAll("/+$", "") + path,
                target.config.getString("api_key"), body.toString().getBytes(StandardCharsets.UTF_8), 16384);
    }

    /** DUMP-only diagnostics: GETs only, no playback, pairing or saved-device changes. */
    static JSONObject probe(Context context, String item) throws Exception {
        JSONObject config = EmbyRecs.readConfig(context), result = new JSONObject();
        JSONArray list = sessions(config), nativeSessions = new JSONArray();
        for (int i = 0; i < list.length() && nativeSessions.length() < 12; i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null || !EmbyPlayerSession.nativeClient(o.optString("Client"))) continue;
            JSONObject clean = new JSONObject();
            for (String key : new String[] {"Id", "DeviceId", "DeviceName", "Client", "ApplicationVersion",
                    "UserId", "UserName", "RemoteEndPoint", "SupportsRemoteControl", "PlayableMediaTypes", "LastActivityDate"})
                clean.put(key, o.opt(key));
            JSONObject state = o.optJSONObject("PlayState"), playing = o.optJSONObject("NowPlayingItem");
            if (state != null) clean.put("PositionTicks", state.optLong("PositionTicks", -1)).put("IsPaused", state.optBoolean("IsPaused"));
            if (playing != null) clean.put("NowPlayingItemId", playing.optString("Id"));
            nativeSessions.put(clean);
        }
        result.put("sessions", nativeSessions).put("localAddresses", new JSONArray(localAddresses()));
        result.put("playerPackage", EmbyNativePlayer.preferred(context));
        EmbyPlayerSession.Session s = null;
        try { s = session(context, config, list); }
        catch (java.io.IOException e) { result.put("selectionError", e.getClass().getSimpleName()); }
        result.put("matched", s != null);
        if (s != null) result.put("ready", s.ready).put("userMatches", config.getString("user_id").equals(s.user))
                .put("client", s.client).put("session", s.id).put("controllerUserMatchesProfile", s.user.equals(s.controllerUser));
        if (item != null && EmbyRecs.validId(item)) {
            EmbyPlayback.Target target = EmbyPlayback.forItem(context, item, "", false);
            result.put("item", target.episode.id).put("positionTicks", target.episode.position);
            if (s != null && s.ready && target.userId.equals(s.user))
                result.put("plannedPath", EmbyPlayerSession.playPath(s, target.userId, target.episode.id, target.episode.position));
        }
        return result;
    }
}
