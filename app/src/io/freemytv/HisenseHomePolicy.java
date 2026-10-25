package io.freemytv;

/** Deliberate stock entry is allowed; every Home request returns to our desktop. */
final class HisenseHomePolicy {
    private long pendingUntil = -1;
    private long lastHome = -1000;

    boolean stock(boolean deliberate, long now) {
        if (deliberate) { pendingUntil = -1; return false; }
        if (pendingUntil < now) { pendingUntil = -1; return false; }
        // If native Home won the launch race, correct it once; do not wait for this window first.
        pendingUntil = -1;
        return true;
    }

    boolean home(long now) {
        if (now - lastHome < 500) return false;
        lastHome = now;
        // Open immediately from the Home signal, retaining only a bounded native-launch fallback.
        pendingUntil = now + 2000;
        return true;
    }

    void reset() { pendingUntil = -1; lastHome = -1000; }
}
