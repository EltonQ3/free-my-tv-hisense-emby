package io.freemytv;

/** After an owned native playback, one Back may request its details, never another play. */
final class EmbyReturnPolicy {
    private long backAt = -1;
    private boolean finished;
    boolean back(long eventAt, long now) {
        if (finished || eventAt < 0 || eventAt > now || now - eventAt > 1500) return false;
        backAt = now; return true;
    }
    boolean waiting() { return !finished && backAt >= 0; }
    void cancel() { finished = true; backAt = -1; }
    boolean show(String screen, long now) {
        if (finished || backAt < 0) return false;
        if (now - backAt > 5000) { backAt = -1; return false; }
        if ("other".equals(screen) || "auth".equals(screen) || "unavailable".equals(screen)) { cancel(); return false; }
        if ("home".equals(screen) || "details".equals(screen)) { cancel(); return true; }
        return false;
    }
}
