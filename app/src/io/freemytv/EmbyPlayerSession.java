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
        final String id, device, user, userName, client, address, version, controllerUser, playerPackage;
        final boolean ready;
        final long activeAt;
        Session(JSONObject o) {
            id = o.optString("Id"); device = o.optString("DeviceId"); user = o.optString("UserId");
            controllerUser = user; playerPackage = "";
            userName = o.optString("UserName"); client = o.optString("Client");
            version = o.optString("ApplicationVersion");
            address = ip(o.optString("RemoteEndPoint"));
            ready = o.optBoolean("SupportsRemoteControl") && has(o.optJSONArray("PlayableMediaTypes"), "Video");
            long at;
            try { at = Instant.parse(o.getString("LastActivityDate")).toEpochMilli(); }
            catch (Exception e) { at = 0; }
            activeAt = at;
        }
        Session(Session transport, Session profile) {
            id = transport.id; device = transport.device; client = transport.client;
            address = transport.address; version = transport.version; ready = transport.ready;
            activeAt = Math.min(transport.activeAt, profile.activeAt);
            // TV's SDK controller retains its startup login. Native AndroidTv is the active,
            // normally authenticated profile; only that profile governs the requested playback.
            controllerUser = transport.user; user = profile.user; userName = profile.userName;
            playerPackage = "tv.emby.embyatv";
        }
        private Session(Session original, String player) {
            id = original.id; device = original.device; user = original.user; userName = original.userName;
            client = original.client; address = original.address; version = original.version;
            controllerUser = original.controllerUser; ready = original.ready; activeAt = original.activeAt;
            playerPackage = player == null ? "" : player;
        }
    }

    static boolean nativeClient(String name) {
        return "Android".equals(name) || "Android TV".equals(name) || "AndroidTv".equals(name)
                || "Emby for Android".equals(name) || "Emby for Android TV".equals(name);
    }

    static boolean matchesPlayer(String name, String pkg) {
        if ("tv.emby.embyatv".equals(pkg)) return "Android TV".equals(name)
                || "AndroidTv".equals(name) || "Emby for Android TV".equals(name);
        if ("com.mb.android".equals(pkg)) return "Android".equals(name) || "Emby for Android".equals(name);
        return false;
    }

    static Session select(JSONArray all, Set<String> localAddresses, String pinnedDevice, long now) throws Exception {
        return select(all, localAddresses, pinnedDevice, now, null);
    }

    static Session select(JSONArray all, Set<String> localAddresses, String pinnedDevice, long now, String player) throws Exception {
        return select(all, localAddresses, pinnedDevice, now, player, "");
    }

    static Session select(JSONArray all, Set<String> localAddresses, String pinnedDevice, long now,
            String player, String installedVersion) throws Exception {
        return select(all, localAddresses, pinnedDevice, now, player, installedVersion, false);
    }

    /** A restored, independently verified native foreground may have an idle cached profile timestamp. */
    static Session select(JSONArray all, Set<String> localAddresses, String pinnedDevice, long now,
            String player, String installedVersion, boolean verifiedForeground) throws Exception {
        return select(all, localAddresses, pinnedDevice, now, player, installedVersion, verifiedForeground, false, "");
    }

    static Session select(JSONArray all, Set<String> localAddresses, String pinnedDevice, long now,
            String player, String installedVersion, boolean verifiedForeground,
            boolean otherEnabled, String otherVersion) throws Exception {
        if (otherEnabled && (installedVersion.isEmpty() || otherVersion.isEmpty()))
            throw new IOException("Installed Emby versions are not known");
        boolean collidingVersions = otherEnabled && installedVersion.equals(otherVersion);
        if (collidingVersions && "com.mb.android".equals(player))
            throw new IOException("Android-named Emby sessions are ambiguous");
        boolean foregroundTv = verifiedForeground && "tv.emby.embyatv".equals(player) && !installedVersion.isEmpty();
        List<Session> candidates = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null) continue;
            Session s = new Session(o);
            if (!(player == null ? nativeClient(s.client) : matchesPlayer(s.client, player))
                    || !installedVersion.isEmpty() && !installedVersion.equals(s.version)
                    || !s.id.matches("[A-Za-z0-9-]{1,200}") || s.device.isEmpty()
                    || s.device.length() > 512 || s.device.indexOf('\n') >= 0 || s.activeAt <= 0
                    || s.activeAt < now - 90000 && !s.ready && !foregroundTv
                    || s.activeAt > now + 60000) continue;
            if (pinnedDevice.isEmpty() ? !localAddresses.contains(s.address) : !pinnedDevice.equals(s.device)) continue;
            candidates.add(s);
        }
        if (candidates.size() > 1) throw new IOException("Ambiguous TV session");
        if (candidates.isEmpty()) return null;
        Session selected = candidates.get(0);
        if (!"tv.emby.embyatv".equals(player) || selected.ready || installedVersion.isEmpty()) return new Session(selected, player);
        if (collidingVersions) throw new IOException("TV controller version is ambiguous");
        // TV 2.5 registers an Android-named transport as well. Distinct installed versions,
        // one native profile and an exact device/address match identify that transport.
        if (selected.activeAt < now - 90000 && !foregroundTv) return new Session(selected, player);
        Session controller = null;
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i); if (o == null) continue;
            Session s = new Session(o);
            if (!matchesPlayer(s.client, "com.mb.android") || !s.ready
                    || !s.id.matches("[A-Za-z0-9-]{1,200}") || s.id.equals(selected.id)
                    || !installedVersion.equals(s.version) || !selected.device.equals(s.device)
                    || !selected.address.equals(s.address)
                    || s.activeAt <= 0 || s.activeAt < now - 90000 && !foregroundTv || s.activeAt > now + 60000) continue;
            if (controller != null) throw new IOException("Ambiguous TV playback controller");
            controller = s;
        }
        return controller == null ? new Session(selected, player) : new Session(controller, selected);
    }

    /** Recheck the selected package and installed versions immediately before a mutation. */
    static void requirePlayer(Session s, String player, String installedVersion,
            boolean otherEnabled, String otherVersion) throws IOException {
        if (s == null || !s.ready || !("tv.emby.embyatv".equals(player) || "com.mb.android".equals(player))
                || !player.equals(s.playerPackage) || installedVersion.isEmpty() || !installedVersion.equals(s.version))
            throw new IOException("Selected Emby player changed");
        if (otherEnabled && matchesPlayer(s.client, "com.mb.android")
                && (otherVersion.isEmpty() || installedVersion.equals(otherVersion)))
            throw new IOException("Android-named Emby controller is ambiguous");
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
