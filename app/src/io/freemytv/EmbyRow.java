package io.freemytv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One wide lead card followed by up to nine real portrait posters. */
final class EmbyRow {
    static final int RESUME = 1, MOVIES = 2, SHOWS = 4, RANDOM = 8;
    static final String[] KINDS = {"resume", "random", "movie", "tv"};

    static List<Recs.Pick> row(List<Recs.Pick> source, String kind) {
        return row(source, kind, 10);
    }

    static List<Recs.Pick> row(List<Recs.Pick> source, String kind, int limit) {
        int mask = "random".equals(kind) ? RANDOM : "movie".equals(kind) ? MOVIES
                : "tv".equals(kind) ? SHOWS : RESUME;
        List<Recs.Pick> ordered = new ArrayList<>();
        for (Recs.Pick p : source) {
            if (p.embyId != null && (p.embyFeeds & mask) != 0
                    && (mask != RESUME || p.resumable)) ordered.add(p);
        }
        if (mask == MOVIES || mask == SHOWS) ordered.sort(Comparator.comparingInt(p -> p.latestRank));
        if (mask == RANDOM) ordered.sort(Comparator.comparingInt(p -> p.randomRank));
        Set<String> seen = new HashSet<>();
        List<Recs.Pick> remaining = new ArrayList<>();
        for (Recs.Pick p : ordered) {
            String group = p.seriesId == null || p.seriesId.isEmpty() ? p.kind + ":" + p.embyId : "series:" + p.seriesId;
            if (seen.add(group)) remaining.add(p);
        }
        List<Recs.Pick> row = new ArrayList<>();
        for (Recs.Pick p : remaining) {
            if (row.isEmpty() || p.posterPath != null) row.add(p);
            if (row.size() == Math.max(1, Math.min(10, limit))) break;
        }
        return row;
    }
}
