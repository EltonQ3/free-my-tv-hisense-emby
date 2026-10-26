package io.freemytv;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Production direct transport and the retained bounded native-UI fallback policy. */
public final class TvBackHandoffCheck {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static EmbyTvPlaybackPolicy policy(long ticks) { return new EmbyTvPlaybackPolicy("Pilot", 1, 2, ticks, 0); }
    static int frame(EmbyTvPlaybackPolicy p, boolean own, boolean editable, int window, String title, String ep, String label, long now) {
        return p.next(own, editable, true, window, title, Collections.singleton(ep),
                Collections.singletonList(new EmbyAccountPolicy.Button(7, label, false)), now);
    }
    static void ui() {
        long ticks = 1250000000L;
        EmbyTvPlaybackPolicy p = policy(ticks);
        check(frame(p, true, false, 5, "Pilot", "S1 E2", "从 2:05 恢复播放", 0) == -1, "wait for the details window to settle");
        check(frame(p, true, false, 6, "Pilot", "S1 E2", "从 2:05 恢复播放", 950) == -1, "a replacement native window resets settling");
        check(frame(p, true, false, 6, "Pilot", "S1 E2", "从 2:05 恢复播放", 1900) == 7, "click exact native resume after settling");
        check(frame(p, true, false, 6, "Pilot", "S1 E2", "从 2:05 恢复播放", 3000) == -1 && p.sent(), "never retry a sent play click");
        for (String[] wrong : new String[][] {{"Other", "S1 E2", "从 2:05 恢复播放"}, {"Pilot", "S1 E3", "从 2:05 恢复播放"},
                {"Pilot", "S1 E2", "从 0:00 恢复播放"}, {"Pilot", "S1 E2", "播放"}, {"Pilot", "S1 E2", "删除"}}) {
            p = policy(ticks); frame(p, true, false, 1, wrong[0], wrong[1], wrong[2], 0);
            check(frame(p, true, false, 1, wrong[0], wrong[1], wrong[2], 1000) == -1 && !p.sent(), "reject wrong title, episode, progress or action");
        }
        for (String label : new String[] {"Resume from 2:05", "從 2:05 恢復播放", "從 2:05 繼續播放"}) {
            p = policy(ticks); frame(p, true, false, 1, "Pilot", "S1 E2", label, 0);
            check(frame(p, true, false, 1, "Pilot", "S1 E2", label, 1000) == 7, "supported native resume labels");
        }
        p = policy(0); frame(p, true, false, 1, "Pilot", "S1 E2", "播放", 0);
        check(frame(p, true, false, 1, "Pilot", "S1 E2", "播放", 1000) == 7, "new episodes use native Play");
        p = policy(ticks); check(frame(p, false, false, 1, "Pilot", "S1 E2", "Resume from 2:05", 0) == -1 && p.cancelled(), "leave other apps untouched");
        p = policy(ticks); check(frame(p, true, true, 1, "Pilot", "S1 E2", "Resume from 2:05", 0) == -1 && p.cancelled(), "never automate password or editable forms");
        p = policy(ticks); check(frame(p, true, false, 1, "Pilot", "S1 E2", "Resume from 2:05", 12001) == -1 && p.cancelled(), "bounded native UI wait");
        p = policy(ticks); frame(p, true, false, 1, "Pilot", "S1 E2", "Resume from 2:05", 0);
        check(p.next(true, false, true, 1, "Pilot", Collections.singleton("s1e2"), Arrays.asList(
                new EmbyAccountPolicy.Button(2,"Resume from 2:05",false), new EmbyAccountPolicy.Button(3,"Resume from 2:05",false)), 1000) == -1 && p.cancelled(), "ambiguous native controls are refused");
        p = new EmbyTvPlaybackPolicy("Movie", -1, -1, 37230000000L, 0);
        frame(p, true, false, 1, "Movie", "", "Resume from 1:02:03", 0);
        check(frame(p, true, false, 1, "Movie", "", "Resume from 1:02:03", 1000) == 7, "hour-long movie resumes remain exact");
    }
    public static void main(String[] args) throws Exception {
        ui();
        List<String> requests = Collections.synchronizedList(new ArrayList<>());
        final Throwable[] failure = {null};
        try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(3000);
            Thread worker = new Thread(() -> {
                try { for (int at = 0; at < 3; at++) try (Socket socket = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder request = new StringBuilder(); int length = 0;
                    for (String line; (line = in.readLine()) != null && !line.isEmpty();) {
                        request.append(line).append('\n');
                        if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                    }
                    request.append('\n');
                    for (int i = 0; i < length; i++) request.append((char) in.read());
                    requests.add(request.toString());
                    socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                } } catch (Throwable error) { failure[0] = error; }
            });
            worker.start();
            EmbyRecs.config = new JSONObject().put("server_url", "http://127.0.0.1:" + server.getLocalPort() + "/emby")
                    .put("server_id", "server-a").put("user_id", "user-a").put("api_key", "dummytestcredential12345");
            EmbyRecs.fixture = (config, path) -> new JSONObject().put("Id", "movie-a").put("Name", "Movie").put("Type", "Movie")
                    .put("UserData", new JSONObject().put("PlaybackPositionTicks", 9106750000L)).toString();
            android.content.Context context = new android.content.Context();
            EmbyPlayback.Target target = EmbyPlayback.forItem(context, "movie-a", "", false);
            EmbyPlayerSession.Session session = TestSessions.player(EmbyNativePlayer.TV, "session-tv", "user-a");
            try { EmbyPlayer.browseItemTv(context, target, session, () -> false); throw new AssertionError("foreground changed"); }
            catch (IOException expected) { checks++; }
            EmbyPlayer.stopTv(context, target, session);
            JSONObject detail = EmbyPlayer.browseItemTv(context, target, session);
            int before = EmbyPlayer.dispatchCount();
            EmbyPlayer.play(context, target, session);
            worker.join(3000);
            check(!worker.isAlive() && failure[0] == null, "production browse completes");
            check(requests.size() == 3, "one stop, one browse and one PlayNow");
            check(requests.get(0).startsWith("POST /emby/Sessions/session-tv/Playing/Stop HTTP/1.1"), "old player stop is separate from new playback");
            String request = requests.get(1);
            check(request.startsWith("POST /emby/Sessions/session-tv/Viewing?ItemId=movie-a&ItemType=Movie HTTP/1.1"), "exact verified item in native browse");
            check(request.substring(request.indexOf("\n\n") + 2).isEmpty(), "empty browse body");
            check(!request.substring(0, request.indexOf('\n')).contains("dummytest"), "credential stays out of URL");
            check(detail.getString("Name").equals("Movie"), "verified metadata returned for the UI check");
            String play = requests.get(2);
            check(play.startsWith("POST /emby/Sessions/session-tv/Playing?ItemIds=movie-a&PlayCommand=PlayNow&StartPositionTicks=9106750000 HTTP/1.1"), "direct route carries exact progress");
            JSONObject owner = new JSONObject(play.substring(play.indexOf("\n\n") + 2));
            check(owner.length() == 1 && owner.getString("ControllingUserId").equals("user-a"), "owner body does not duplicate query parameters");
            check(EmbyPlayer.dispatchCount() == before + 1, "one direct playback dispatch only");
            EmbyNativePlayer.other = true; EmbyNativePlayer.androidVersion = EmbyNativePlayer.tvVersion;
            try { EmbyPlayer.browseItemTv(context, target, TestSessions.tvAlias("ambiguous-control", "user-a")); throw new AssertionError("colliding controller"); }
            catch (IOException expected) { checks++; }
            EmbyNativePlayer.other = false; EmbyNativePlayer.androidVersion = "3.5.35";
            EmbyRecs.config.put("user_id", "changed-user");
            try { EmbyPlayer.play(context, target, session); throw new AssertionError("stale profile"); }
            catch (IOException expected) { checks++; }
            server.setSoTimeout(250);
            try (Socket unexpected = server.accept()) { throw new AssertionError("unexpected retry or playback command"); }
            catch (SocketTimeoutException expected) { checks++; }
        }
        // A failed/ambiguous response cannot trigger a second POST or a speculative UI retry.
        try (ServerSocket failed = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            failed.setSoTimeout(500);
            final int[] attempts = {0}; final Throwable[] error = {null};
            Thread worker = new Thread(() -> {
                try {
                    while (true) try (Socket socket = failed.accept()) {
                        attempts[0]++;
                        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        int length = 0;
                        for (String line; (line = in.readLine()) != null && !line.isEmpty();)
                            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                        for (int i = 0; i < length; i++) in.read();
                        socket.getOutputStream().write("HTTP/1.1 500 Failed\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    }
                } catch (SocketTimeoutException expected) { }
                catch (Throwable e) { error[0] = e; }
            });
            EmbyRecs.config = new JSONObject().put("server_url", "http://127.0.0.1:" + failed.getLocalPort() + "/emby")
                    .put("server_id", "server-a").put("user_id", "user-a").put("api_key", "dummytestcredential12345");
            android.content.Context context = new android.content.Context();
            EmbyPlayback.Target target = EmbyPlayback.forItem(context, "movie-a", "", false);
            EmbyPlayerSession.Session session = TestSessions.player(EmbyNativePlayer.TV, "session-tv", "user-a");
            int before = EmbyPlayer.dispatchCount(); worker.start();
            try { EmbyPlayer.play(context, target, session); throw new AssertionError("HTTP error must be visible"); }
            catch (IOException expected) { checks++; }
            worker.join(2000);
            check(!worker.isAlive() && error[0] == null, "failed response fixture completes");
            check(attempts[0] == 1 && EmbyPlayer.dispatchCount() == before + 1, "failed PlayNow has one attempt and no retry");
        }
        System.out.println("PASS: " + checks + " native TV playback / Back regression assertions.");
    }
}
