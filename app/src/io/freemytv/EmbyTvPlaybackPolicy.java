package io.freemytv;

import java.util.List;
import java.util.Locale;

/** One normal native button click, only on the exact verified title/episode and resume label. */
final class EmbyTvPlaybackPolicy {
    private final String title, episode;
    private final long ticks, deadline;
    private int window = -1;
    private long stableAt;
    private boolean sent, cancelled;
    EmbyTvPlaybackPolicy(String title, int season, int number, long ticks, long now) {
        this.title = norm(title); this.episode = season >= 0 && number >= 0 ? "s" + season + "e" + number : "";
        this.ticks = ticks; deadline = now + 12000;
        if (this.title.isEmpty() || ticks < 0) cancelled = true;
    }
    boolean sent() { return sent; }
    boolean cancelled() { return cancelled; }
    int next(boolean owned, boolean editable, boolean details, int windowId, String actualTitle,
             java.util.Set<String> texts, List<EmbyAccountPolicy.Button> buttons, long now) {
        if (cancelled || sent) return -1;
        if (!owned || editable || now >= deadline) { cancelled = true; return -1; }
        if (!details || !title.equals(norm(actualTitle))) return -1;
        if (!episode.isEmpty()) {
            boolean found = false;
            for (String value : texts) if (value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT).equals(episode)) found = true;
            if (!found) return -1;
        }
        if (window != windowId) { window = windowId; stableAt = now; return -1; }
        if (now - stableAt < 900) return -1;
        int chosen = -1;
        for (EmbyAccountPolicy.Button b : buttons) {
            String value = norm(b.text);
            boolean match = ticks == 0 ? value.equals("播放") || value.equals("play")
                    : resume(value) && correctTime(value, ticks);
            if (match) { if (chosen >= 0 && chosen != b.id) { cancelled = true; return -1; } chosen = b.id; }
        }
        if (chosen >= 0) sent = true; // Set before action: never retry a native play click.
        return chosen;
    }
    private static boolean resume(String s) {
        return s.contains("resume") || s.contains("恢复播放") || s.contains("恢復播放") || s.contains("繼續播放");
    }
    private static boolean correctTime(String s, long ticks) {
        long seconds = ticks / 10000000L;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?<![0-9:])(\\d+):(\\d{2})(?::(\\d{2}))?(?![0-9:])").matcher(s);
        if (!m.find()) return false;
        try {
            long a = Long.parseLong(m.group(1)), b = Long.parseLong(m.group(2));
            long displayed = m.group(3) == null ? a * 60 + b : a * 3600 + b * 60 + Long.parseLong(m.group(3));
            return Math.abs(seconds - displayed) <= 1;
        } catch (NumberFormatException e) { return false; }
    }
    private static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
}
