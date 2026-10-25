package io.freemytv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Resolve a series against this user's current progress, independently of its latest-added episode. */
final class EmbyProgress {
    static final class Episode {
        String id, seriesId;
        boolean played, unavailable;
        long position;
        int season = Integer.MAX_VALUE, number = Integer.MAX_VALUE;
    }

    interface Source {
        List<Episode> resume(String user, String series) throws Exception;
        List<Episode> nextUp(String user, String series) throws Exception;
        List<Episode> unplayed(String user, String series) throws Exception;
    }

    static Episode resolve(Source source, String user, String series) throws Exception {
        if (!valid(user) || !valid(series)) throw new IllegalArgumentException();
        // A failed progress query must not fall back to the newest episode and skip what the user was watching.
        for (Episode e : source.resume(user, series))
            if (eligible(e, series) && e.position > 0) return e;
        for (Episode e : source.nextUp(user, series)) if (eligible(e, series)) return e;
        List<Episode> remaining = new ArrayList<>();
        for (Episode e : source.unplayed(user, series))
            if (eligible(e, series) && e.season != 0) remaining.add(e);
        remaining.sort(Comparator.comparingInt((Episode e) -> e.season).thenComparingInt(e -> e.number));
        return remaining.isEmpty() ? null : remaining.get(0);
    }

    private static boolean eligible(Episode e, String series) {
        return e != null && valid(e.id) && series.equals(e.seriesId) && !e.played && !e.unavailable;
    }

    private static boolean valid(String id) { return id != null && id.matches("[A-Za-z0-9-]{1,128}"); }
}
