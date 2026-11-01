package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class HiddenTest {
    private static final String TODAY = "2026-11-01";

    private static TraktClient.Item item(String type, int tmdb) {
        TraktClient.Item i = new TraktClient.Item();
        i.type = type;
        i.tmdbId = tmdb;
        i.title = type + tmdb;
        return i;
    }

    @Test
    public void moviesAreHiddenUnlessReleased() {
        assertFalse(Tmdb.isUnreleased(true, "Released", "2020-01-01", TODAY));
        assertTrue(Tmdb.isUnreleased(true, "Post Production", "", TODAY));
        assertTrue(Tmdb.isUnreleased(true, "In Production", "2027-05-01", TODAY));
        assertTrue(Tmdb.isUnreleased(true, "Rumored", "", TODAY));
        assertFalse(Tmdb.isUnreleased(true, "", "", TODAY));   // no status at all: shown
    }

    @Test
    public void seriesAreHiddenUntilTheyHaveAired() {
        assertTrue(Tmdb.isUnreleased(false, "Planned", "", TODAY));
        assertTrue(Tmdb.isUnreleased(false, "In Production", "2020-01-01", TODAY));
        assertTrue(Tmdb.isUnreleased(false, "Returning Series", "2027-01-01", TODAY));
        assertTrue(Tmdb.isUnreleased(false, "", "", TODAY));
        assertTrue(Tmdb.isUnreleased(false, "Pilot", "", TODAY));
        assertFalse(Tmdb.isUnreleased(false, "Returning Series", "2024-03-02", TODAY));
        assertFalse(Tmdb.isUnreleased(false, "Ended", "", TODAY));
        assertFalse(Tmdb.isUnreleased(false, "Canceled", "1999-09-09", TODAY));
        assertFalse(Tmdb.isUnreleased(false, "Ended", "2026-11-01", TODAY));   // airs today
    }

    @Test
    public void releasedStaysForeverUnreleasedIsCheckedAfterAWeek() {
        long day = 24L * 60 * 60 * 1000, now = 100 * day;
        assertTrue(Tmdb.needsStatus(true, 0, "", "", now, TODAY));                       // never fetched
        assertFalse(Tmdb.needsStatus(true, now - 90 * day, "Released", "2019-01-01", now, TODAY));
        assertFalse(Tmdb.needsStatus(true, now - 6 * day, "Planned", "", now, TODAY));
        assertTrue(Tmdb.needsStatus(true, now - 8 * day, "Planned", "", now, TODAY));
        assertFalse(Tmdb.needsStatus(false, now - 30 * day, "Ended", "2010-01-01", now, TODAY));
        assertTrue(Tmdb.needsStatus(false, now - 8 * day, "Planned", "", now, TODAY));
    }

    @Test
    public void keysSeparateMoviesFromSeries() {
        assertEquals("movie:5", Hidden.keyOf(item("movie", 5)));
        assertEquals("show:5", Hidden.keyOf(item("show", 5)));
    }

    @Test
    public void noTmdbManualAndUnreleasedAreLeftOut() {
        TraktClient.Item noId = item("movie", 0), manual = item("movie", 2), later = item("show", 3), ok = item("movie", 4),
                sameIdShow = item("show", 2), unknown = item("show", 6);
        Set<String> hidden = new HashSet<>(Arrays.asList("movie:2"));
        // Unknown status (like 6) is not hidden; only 3 is known to be unreleased.
        java.util.function.Predicate<TraktClient.Item> unreleased = it -> it.tmdbId == 3;
        List<TraktClient.Item> all = Arrays.asList(noId, manual, later, ok, sameIdShow, unknown);
        List<TraktClient.Item> shown = Hidden.visible(all, hidden, unreleased);
        assertEquals(Arrays.asList(ok, sameIdShow, unknown), shown);
        assertEquals(Hidden.NO_TMDB, Hidden.reason(noId, hidden, unreleased));
        assertEquals(Hidden.MANUAL, Hidden.reason(manual, hidden, unreleased));
        assertEquals(Hidden.UNRELEASED, Hidden.reason(later, hidden, unreleased));
        assertEquals(Hidden.VISIBLE, Hidden.reason(ok, hidden, unreleased));
    }

    @Test
    public void nothingToFilterKeepsEverything() {
        List<TraktClient.Item> all = new ArrayList<>(Arrays.asList(item("movie", 1), item("show", 2)));
        assertEquals(all, Hidden.visible(all, new HashSet<String>(), null));
        assertTrue(Hidden.visible(new ArrayList<TraktClient.Item>(), null, null).isEmpty());
    }
}
