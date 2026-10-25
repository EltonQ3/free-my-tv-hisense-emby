package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Private, per-profile Emby recommendations. Credentials stay in the app's private files. */
final class EmbyRecs {
    private EmbyRecs() {}
    private static final Map<String, PosterEntry> seriesPosterCache = new LinkedHashMap<>();
    private static final class PosterEntry {
        final String path;
        final long at = System.currentTimeMillis();
        PosterEntry(String path) { this.path = path; }
    }

    static JSONObject readConfig(Context context) throws Exception {
        JSONObject o;
        try (InputStream in = new FileInputStream(new File(context.getFilesDir(), "emby-connection.json"))) {
            o = new JSONObject(read(in, 16384));
        }
        URI server = new URI(o.getString("server_url"));
        if (!("http".equals(server.getScheme()) || "https".equals(server.getScheme()))
                || server.getHost() == null || server.getRawUserInfo() != null || server.getRawQuery() != null
                || server.getRawFragment() != null || !validId(o.optString("user_id"))
                || !validId(o.optString("server_id")) || o.optString("api_key").trim().isEmpty()) {
            throw new IOException("Emby configuration unavailable");
        }
        return o;
    }

    static boolean configured(Context context) {
        return new File(context.getFilesDir(), "emby-connection.json").isFile();
    }

    static String identity(Context context) {
        try {
            JSONObject o = readConfig(context);
            return digest(o.getString("server_url") + "\n" + o.getString("server_id") + "\n" + o.getString("user_id"));
        } catch (Exception ignored) {
            return "";
        }
    }

    static String request(Context context, String path) throws Exception {
        return new String(fetchBytes(context, path, 4 * 1024 * 1024), StandardCharsets.UTF_8);
    }

    static String request(JSONObject config, String path) throws Exception {
        return new String(fetchBytes(config, path, 4 * 1024 * 1024), StandardCharsets.UTF_8);
    }

    private static byte[] fetchBytes(Context context, String path, int limit) throws Exception {
        return fetchBytes(readConfig(context), path, limit);
    }

    static byte[] fetchBytes(JSONObject config, String path, int limit) throws Exception {
        if (!path.startsWith("/") || path.startsWith("//") || path.indexOf('#') >= 0 || path.indexOf('\\') >= 0
                || path.contains("/../") || path.contains("/./")) throw new IOException("Invalid Emby request");
        String server = config.getString("server_url").replaceAll("/+$", "");
        return EmbyHttp.get(server + path, config.getString("api_key"), limit);
    }

    static void saveConfig(Context context, JSONObject config) throws Exception {
        JSONObject clean = new JSONObject();
        clean.put("server_url", EmbyHttp.server(config.getString("server_url")));
        for (String field : new String[] {"server_id", "user_id", "device_id"}) {
            if (!config.getString(field).matches("[A-Za-z0-9_-]{1,200}")) throw new IOException("Invalid configuration");
            clean.put(field, config.getString(field));
        }
        if (!config.getString("api_key").matches("[A-Za-z0-9_-]{16,256}")) throw new IOException("Invalid API key");
        clean.put("api_key", config.getString("api_key"));
        clean.put("user_name", config.optString("user_name"));
        android.util.AtomicFile file = new android.util.AtomicFile(new File(context.getFilesDir(), "emby-connection.json"));
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(clean.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
    }

    static List<Recs.Pick> fetch(Context context) throws Exception {
        JSONObject config = readConfig(context);
        String user = config.getString("user_id");
        String fields = "&EnableImages=true&ImageTypeLimit=1&Fields=Genres,ParentId,PrimaryImageAspectRatio";
        Map<String, Recs.Pick> picks = new LinkedHashMap<>();
        JSONObject resume = new JSONObject(request(context, "/Users/" + user
                + "/Items/Resume?Limit=36&MediaTypes=Video" + fields));
        List<JSONArray> lists = new ArrayList<>();
        lists.add(resume.optJSONArray("Items"));
        for (String type : new String[] {"Movie", "Episode"}) {
            JSONArray latest = new JSONArray(request(context, "/Users/" + user + "/Items/Latest?Limit="
                    + ("Movie".equals(type) ? 36 : 96)
                    + "&IncludeItemTypes=" + type + "&GroupItems=false" + fields));
            lists.add(latest);
        }
        for (String type : new String[] {"Movie", "Episode"}) {
            try {
                JSONObject random = new JSONObject(request(context, "/Users/" + user
                        + "/Items?Recursive=true&Limit=48&IncludeItemTypes=" + type
                        + "&SortBy=Random&Filters=IsUnplayed&EnableTotalRecordCount=false" + fields));
                lists.add(random.optJSONArray("Items"));
            } catch (Exception ignored) {
                lists.add(null);   // A random-feed failure must not hide progress or latest additions.
            }
        }
        Map<String, String> seriesPosters = seriesPosters(context, user, lists);
        int[] feeds = {EmbyRow.RESUME, EmbyRow.MOVIES, EmbyRow.SHOWS, EmbyRow.RANDOM, EmbyRow.RANDOM};
        for (int i = 0; i < lists.size(); i++) add(picks, lists.get(i), feeds[i], i == 4 ? 1 : 0, context, seriesPosters);
        return new ArrayList<>(picks.values());
    }

    private static void add(Map<String, Recs.Pick> out, JSONArray items, int feed, int randomOffset, Context context,
                            Map<String, String> seriesPosters) throws Exception {
        String identity = identity(context);
        String serverId = readConfig(context).getString("server_id");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String type = o.optString("Type");
            String id = o.optString("Id");
            if (!("Movie".equals(type) || "Episode".equals(type)) || !validId(id)) continue;
            Recs.Pick existing = out.get(id);
            if (existing != null) {
                existing.embyFeeds |= feed;
                if (feed == EmbyRow.MOVIES || feed == EmbyRow.SHOWS) existing.latestRank = i;
                if (feed == EmbyRow.RANDOM) existing.randomRank = i * 2 + randomOffset;
                continue;
            }
            JSONObject data = o.optJSONObject("UserData");
            Recs.Pick p = new Recs.Pick();
            p.embyId = id;
            p.id = id.hashCode();
            p.kind = "Movie".equals(type) ? "movie" : "tv";
            p.title = o.optString("Name");
            String series = o.optString("SeriesName");
            p.seriesTitle = series;
            if (!series.isEmpty() && "Episode".equals(type)) p.title = series + " · " + p.title;
            p.positionTicks = data == null ? 0 : Math.max(0, data.optLong("PlaybackPositionTicks"));
            p.runtimeTicks = Math.max(0, o.optLong("RunTimeTicks"));
            p.resumable = p.positionTicks > 0;
            p.seriesId = o.optString("SeriesId");
            p.embyFeeds = feed;
            if (feed == EmbyRow.MOVIES || feed == EmbyRow.SHOWS) p.latestRank = i;
            if (feed == EmbyRow.RANDOM) p.randomRank = i * 2 + randomOffset;
            p.imagePath = imagePath(o, id);
            if (p.imagePath == null) continue;
            p.posterPath = posterPath(o, id, seriesPosters);
            p.posterKey = p.posterPath == null ? null : "emby-poster-" + digest(identity + "\n" + p.posterPath) + ".jpg";
            p.backdrop = "emby-" + digest(identity + "\n" + p.imagePath + "\n" + p.posterPath) + ".jpg";
            p.app = "emby";
            p.pkg = "com.mb.android";
            // The installed Emby player reads its current profile's latest saved playback position.
            p.link = "emby://play/" + serverId + "/" + id;
            p.detailed = true;
            List<String> meta = new ArrayList<>();
            if (p.kind.equals("tv")) meta.add(Ui.tr("Series"));
            int season = o.optInt("ParentIndexNumber", -1), episode = o.optInt("IndexNumber", -1);
            if (season >= 0 && episode >= 0) meta.add("S" + season + "E" + episode);
            if (p.resumable && p.runtimeTicks > 0) {
                meta.add(Math.min(99, p.positionTicks * 100 / p.runtimeTicks) + "%");
            } else if (p.runtimeTicks > 0) {
                int minutes = (int) (p.runtimeTicks / 600000000L);
                if (minutes > 0) meta.add(minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60)
                        : Ui.trf("{m}m", "m", minutes));
            }
            meta.add("Emby");
            p.meta = String.join(" · ", meta);
            out.put(id, p);
        }
    }

    private static String posterPath(JSONObject item, String id, Map<String, String> seriesPosters) throws Exception {
        boolean episode = "Episode".equals(item.optString("Type"));
        String series = item.optString("SeriesId"), seriesTag = item.optString("SeriesPrimaryImageTag");
        if (episode && validId(series)) {
            if (!seriesTag.isEmpty()) return poster(series, seriesTag);
            String cached = seriesPosters.get(series);
            if (cached != null) return cached;
        }
        JSONObject tags = item.optJSONObject("ImageTags");
        String tag = tags == null ? "" : tags.optString("Primary");
        // Unmatched movies may have only an automatically generated landscape frame as Primary.
        if (!episode && !tag.isEmpty() && item.optDouble("PrimaryImageAspectRatio", 0) <= 1.05) return poster(id, tag);
        String parent = item.optString("ParentPrimaryImageItemId");
        String parentTag = item.optString("ParentPrimaryImageTag");
        if (validId(parent) && !parentTag.isEmpty()) return poster(parent, parentTag);
        // An episode's Primary is a landscape still, never a substitute for a series poster.
        return null;
    }

    private static String poster(String id, String tag) throws Exception {
        return "/Items/" + id + "/Images/Primary?maxWidth=600&quality=90&tag=" + URLEncoder.encode(tag, "UTF-8");
    }

    /** Older servers omit series tags on episode DTOs. Look those up together, once per cache period. */
    private static Map<String, String> seriesPosters(Context context, String user, List<JSONArray> lists) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> missing = new HashSet<>();
        String identity = identity(context);
        long now = System.currentTimeMillis();
        for (JSONArray list : lists) for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null || !"Episode".equals(item.optString("Type"))) continue;
            String series = item.optString("SeriesId");
            if (!validId(series) || !item.optString("SeriesPrimaryImageTag").isEmpty()) continue;
            PosterEntry cached = seriesPosterCache.get(identity + ":" + series);
            if (cached != null && now >= cached.at && now - cached.at < 15 * 60_000L) {
                if (cached.path != null) out.put(series, cached.path);
            } else missing.add(series);
        }
        if (missing.isEmpty()) return out;
        Map<String, String> found = new LinkedHashMap<>();
        try {
            JSONObject result = new JSONObject(request(context, "/Users/" + user + "/Items?Ids="
                    + String.join(",", missing) + "&EnableImages=true&ImageTypeLimit=1"));
            JSONArray items = result.optJSONArray("Items");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("Id");
                JSONObject tags = item.optJSONObject("ImageTags");
                String tag = tags == null ? "" : tags.optString("Primary");
                if (missing.contains(id) && !tag.isEmpty()) found.put(id, poster(id, tag));
            }
        } catch (Exception ignored) { }
        for (String id : missing) seriesPosterCache.put(identity + ":" + id, new PosterEntry(found.get(id)));
        while (seriesPosterCache.size() > 64) seriesPosterCache.remove(seriesPosterCache.keySet().iterator().next());
        out.putAll(found);
        return out;
    }

    private static String imagePath(JSONObject item, String id) throws Exception {
        String parent = item.optString("ParentBackdropItemId");
        JSONArray parentTags = item.optJSONArray("ParentBackdropImageTags");
        if ("Episode".equals(item.optString("Type")) && validId(parent) && parentTags != null && parentTags.length() > 0) {
            return image(parent, "Backdrop/0", parentTags.optString(0));
        }
        JSONArray backdrops = item.optJSONArray("BackdropImageTags");
        if (backdrops != null && backdrops.length() > 0) return image(id, "Backdrop/0", backdrops.optString(0));
        if (validId(parent) && parentTags != null && parentTags.length() > 0) return image(parent, "Backdrop/0", parentTags.optString(0));
        JSONObject tags = item.optJSONObject("ImageTags");
        if (tags != null) {
            for (String type : new String[] {"Thumb", "Primary"}) {
                String tag = tags.optString(type);
                if (!tag.isEmpty()) return image(id, type, tag);
            }
        }
        return null;
    }

    private static String image(String id, String type, String tag) throws Exception {
        return "/Items/" + id + "/Images/" + type + "?maxWidth=" + Math.max(1280, Backdrop.W)
                + "&quality=85&tag=" + URLEncoder.encode(tag, "UTF-8");
    }

    static void download(Context context, Recs.Pick pick, File file) throws Exception {
        downloadImage(context, pick.imagePath, file);
    }

    static void downloadPoster(Context context, Recs.Pick pick, File file) throws Exception {
        if (pick.posterPath == null || !pick.posterPath.matches("/Items/[A-Za-z0-9-]+/Images/Primary\\?.*")) {
            throw new IOException("Emby poster unavailable");
        }
        downloadImage(context, pick.posterPath, file);
    }

    private static void downloadImage(Context context, String path, File file) throws Exception {
        if (file.exists()) return;
        if (path == null || !path.matches("/Items/[A-Za-z0-9-]+/Images/(Backdrop/0|Thumb|Primary)\\?.*")) {
            throw new IOException("Invalid Emby image");
        }
        file.getParentFile().mkdirs();
        File part = new File(file.getPath() + ".part");
        try {
            byte[] bytes = fetchBytes(context, path, 12 * 1024 * 1024);
            try (FileOutputStream out = new FileOutputStream(part)) { out.write(bytes); }
            if (!part.renameTo(file)) throw new IOException("Could not save Emby image");
        } finally { part.delete(); }
    }

    static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9-]{1,128}");
    }

    private static String digest(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    private static String read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int n;
        while ((n = in.read(bytes)) > 0) {
            if (out.size() + n > max) throw new IOException("Emby response too large");
            out.write(bytes, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
