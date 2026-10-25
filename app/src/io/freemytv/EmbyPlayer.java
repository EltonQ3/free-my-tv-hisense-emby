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

/** Native-player handoff. The only write is the one PlayNow explicitly requested by a poster click. */
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
        SharedPreferences prefs = context.getSharedPreferences(PIN, Context.MODE_PRIVATE);
        String pinned = config.getString("server_id").equals(prefs.getString("server", ""))
                ? prefs.getString("device", "") : "";
        return EmbyPlayerSession.select(list, localAddresses(), pinned, System.currentTimeMillis());
    }

    static boolean current(Context context, EmbyPlayback.Target target) {
        try { return target.config != null && EmbyPlayerSession.sameConnection(target.config, EmbyRecs.readConfig(context)); }
        catch (Exception e) { return false; }
    }

    static void pin(Context context, EmbyPlayback.Target target, EmbyPlayerSession.Session session) {
        context.getSharedPreferences(PIN, Context.MODE_PRIVATE).edit()
                .putString("server", target.serverId).putString("device", session.device).apply();
    }

    /** No retry after dispatch: a lost response must not start the item twice or fall back to zero. */
    static void play(Context context, EmbyPlayback.Target target, EmbyPlayerSession.Session session) throws Exception {
        if (!current(context, target)) throw new IOException("Connection changed");
        String path = EmbyPlayerSession.playPath(session, target.userId, target.episode.id, target.episode.position);
        JSONObject body = EmbyPlayerSession.playBody(session, target.userId, target.episode.id, target.episode.position);
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
            nativeSessions.put(clean);
        }
        result.put("sessions", nativeSessions).put("localAddresses", new JSONArray(localAddresses()));
        EmbyPlayerSession.Session s = session(context, config, list);
        result.put("matched", s != null);
        if (s != null) result.put("ready", s.ready).put("userMatches", config.getString("user_id").equals(s.user))
                .put("client", s.client).put("session", s.id);
        if (item != null && EmbyRecs.validId(item)) {
            EmbyPlayback.Target target = EmbyPlayback.forItem(context, item, "", false);
            result.put("item", target.episode.id).put("positionTicks", target.episode.position);
            if (s != null && s.ready && target.userId.equals(s.user))
                result.put("plannedPath", EmbyPlayerSession.playPath(s, target.userId, target.episode.id, target.episode.position));
        }
        return result;
    }
}
