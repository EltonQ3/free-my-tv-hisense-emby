package io.freemytv;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Read-only, click-time queries. Never writes watched state or starts playback on the server. */
final class EmbyPlayback {
    static final class Target {
        final String userId, serverId;
        final EmbyProgress.Episode episode;
        final JSONObject config;
        Target(String userId, String serverId, EmbyProgress.Episode episode) {
            this(userId, serverId, episode, null);
        }
        Target(String userId, String serverId, EmbyProgress.Episode episode, JSONObject config) {
            this.userId = userId; this.serverId = serverId; this.episode = episode;
            this.config = config;
        }
        String link() { return "emby://play/" + serverId + "/" + episode.id; }
    }

    static String resolve(Context context, String series) throws Exception {
        Target target = target(context, series);
        return target == null ? null : target.link();
    }

    static Target account(Context context) throws Exception {
        JSONObject config = EmbyRecs.readConfig(context);
        EmbyProgress.Episode none = new EmbyProgress.Episode(); none.id = "";
        return new Target(config.getString("user_id"), config.getString("server_id"), none, config);
    }

    static Target target(Context context, String series) throws Exception {
        final JSONObject config = EmbyRecs.readConfig(context);
        return target(config, series);
    }

    private static Target target(JSONObject config, String series) throws Exception {
        EmbyProgress.Episode episode = EmbyProgress.resolve(new EmbyProgress.Source() {
            private List<EmbyProgress.Episode> get(String path) throws Exception {
                JSONArray items = new JSONObject(EmbyRecs.request(config, path)).optJSONArray("Items");
                if (items == null) throw new java.io.IOException("Invalid Emby progress response");
                return episodes(items);
            }
            @Override public List<EmbyProgress.Episode> resume(String user, String show) throws Exception {
                return get("/Users/" + user + "/Items?Recursive=true&ParentId=" + show
                        + "&IncludeItemTypes=Episode&Filters=IsResumable&SortBy=DatePlayed&SortOrder=Descending"
                        + "&Limit=10&EnableUserData=true&EnableImages=false");
            }
            @Override public List<EmbyProgress.Episode> nextUp(String user, String show) throws Exception {
                return get("/Shows/NextUp?UserId=" + user + "&SeriesId=" + show
                        + "&Limit=10&EnableUserData=true&EnableImages=false");
            }
            @Override public List<EmbyProgress.Episode> unplayed(String user, String show) throws Exception {
                return get("/Shows/" + show + "/Episodes?UserId=" + user
                        + "&IsPlayed=false&IsSpecialSeason=false&IsMissing=false&Limit=50"
                        + "&EnableUserData=true&EnableImages=false");
            }
        }, config.getString("user_id"), series);
        return episode == null ? null : new Target(config.getString("user_id"), config.getString("server_id"), episode, config);
    }

    /** Movies and explicit continue cards also fetch the selected user's position again at click time. */
    static Target forItem(Context context, String item, String series, boolean resolveSeries) throws Exception {
        JSONObject config = EmbyRecs.readConfig(context);
        if (resolveSeries) return target(config, series);
        if (!EmbyRecs.validId(item)) throw new java.io.IOException("Invalid item");
        JSONObject result = new JSONObject(EmbyRecs.request(config, "/Users/" + config.getString("user_id")
                + "/Items/" + item + "?EnableUserData=true&EnableImages=false"));
        if (!item.equals(result.optString("Id")) || !("Movie".equals(result.optString("Type"))
                || "Episode".equals(result.optString("Type"))) || result.optBoolean("IsMissing")
                || result.optBoolean("IsVirtualItem") || result.optBoolean("IsPlaceHolder"))
            throw new java.io.IOException("Unavailable item");
        JSONObject data = result.optJSONObject("UserData");
        if (data == null) throw new java.io.IOException("Missing progress");
        EmbyProgress.Episode e = new EmbyProgress.Episode();
        e.id = item; e.seriesId = result.optString("SeriesId");
        e.position = Math.max(0, data.optLong("PlaybackPositionTicks"));
        return new Target(config.getString("user_id"), config.getString("server_id"), e, config);
    }

    private static List<EmbyProgress.Episode> episodes(JSONArray items) {
        List<EmbyProgress.Episode> out = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !"Episode".equals(item.optString("Type"))) continue;
            EmbyProgress.Episode e = new EmbyProgress.Episode();
            e.id = item.optString("Id"); e.seriesId = item.optString("SeriesId");
            e.season = item.optInt("ParentIndexNumber", Integer.MAX_VALUE);
            e.number = item.optInt("IndexNumber", Integer.MAX_VALUE);
            JSONObject data = item.optJSONObject("UserData");
            e.position = data == null ? 0 : Math.max(0, data.optLong("PlaybackPositionTicks"));
            e.played = data != null && data.optBoolean("Played");
            e.unavailable = item.optBoolean("IsMissing") || item.optBoolean("IsVirtualItem")
                    || item.optBoolean("IsPlaceHolder") || "Virtual".equals(item.optString("LocationType"));
            out.add(e);
        }
        return out;
    }
}
