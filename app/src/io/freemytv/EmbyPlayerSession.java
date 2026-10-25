package io.freemytv;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Strict selection of this TV's native Emby client. Never picks an arbitrary server session. */
final class EmbyPlayerSession {
    static final class Session {
        final String id, device, user, userName, client, address;
        final boolean ready;
        final long activeAt;
        Session(JSONObject o) {
            id = o.optString("Id"); device = o.optString("DeviceId"); user = o.optString("UserId");
            userName = o.optString("UserName"); client = o.optString("Client");
            address = ip(o.optString("RemoteEndPoint"));
            ready = o.optBoolean("SupportsRemoteControl") && has(o.optJSONArray("PlayableMediaTypes"), "Video");
            long at;
            try { at = Instant.parse(o.getString("LastActivityDate")).toEpochMilli(); }
            catch (Exception e) { at = 0; }
            activeAt = at;
        }
    }

    static boolean nativeClient(String name) {
        return "Android".equals(name) || "Android TV".equals(name) || "AndroidTv".equals(name)
                || "Emby for Android".equals(name) || "Emby for Android TV".equals(name);
    }

    static Session select(JSONArray all, Set<String> localAddresses, String pinnedDevice, long now) throws Exception {
        List<Session> candidates = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null) continue;
            Session s = new Session(o);
            if (!nativeClient(s.client) || !s.id.matches("[A-Za-z0-9-]{1,200}") || s.device.isEmpty()
                    || s.device.length() > 512 || s.device.indexOf('\n') >= 0 || s.activeAt <= 0
                    || s.activeAt < now - 90000 && !s.ready
                    || s.activeAt > now + 60000) continue;
            if (pinnedDevice.isEmpty() ? !localAddresses.contains(s.address) : !pinnedDevice.equals(s.device)) continue;
            candidates.add(s);
        }
        if (candidates.size() > 1) throw new IOException("Ambiguous TV session");
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    static String playPath(Session session, String user, String item, long ticks) throws Exception {
        if (session == null || !session.ready || !session.user.equals(user)
                || !session.id.matches("[A-Za-z0-9-]{1,200}")
                || !user.matches("[A-Za-z0-9-]{1,128}") || !item.matches("[A-Za-z0-9-]{1,128}") || ticks < 0)
            throw new IOException("Playback session does not match");
        return "/Sessions/" + session.id + "/Playing?ItemIds=" + item
                + "&PlayCommand=PlayNow&StartPositionTicks=" + ticks;
    }

    static JSONObject playBody(Session session, String user, String item, long ticks) throws Exception {
        playPath(session, user, item, ticks);
        return new JSONObject().put("ControllingUserId", user).put("ItemIds", new JSONArray().put(item))
                .put("PlayCommand", "PlayNow").put("StartPositionTicks", ticks);
    }

    static boolean sameConnection(JSONObject a, JSONObject b) {
        for (String key : new String[] {"server_url", "server_id", "user_id", "api_key"})
            if (!a.optString(key).equals(b.optString(key))) return false;
        return true;
    }

    static String ip(String raw) {
        String s = raw.trim();
        if (s.startsWith("[")) { int end = s.indexOf(']'); if (end > 0) s = s.substring(1, end); }
        else if (s.indexOf(':') == s.lastIndexOf(':') && s.indexOf(':') >= 0) s = s.substring(0, s.indexOf(':'));
        if (!s.matches("[0-9A-Fa-f:.%]+")) return "";
        try { return InetAddress.getByName(s).getHostAddress(); } catch (Exception e) { return ""; }
    }

    private static boolean has(JSONArray values, String value) {
        for (int i = 0; values != null && i < values.length(); i++) if (value.equals(values.optString(i))) return true;
        return false;
    }
}
