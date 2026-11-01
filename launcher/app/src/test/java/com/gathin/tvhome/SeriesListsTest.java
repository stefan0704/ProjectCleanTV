package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SeriesListsTest {
    private static TraktClient.Item show() {
        TraktClient.Item s = new TraktClient.Item();
        s.type = "show";
        s.title = "Dark";
        s.tmdbId = 70523;
        return s;
    }

    private static final String PROGRESS = "{\"aired\":6,\"completed\":3,"
            + "\"next_episode\":{\"season\":2,\"number\":1,\"title\":\"Despertar\",\"ids\":{\"trakt\":9}},"
            + "\"seasons\":[{\"number\":1,\"aired\":3,\"completed\":3,\"episodes\":["
            + "{\"number\":1,\"completed\":true},{\"number\":2,\"completed\":true},{\"number\":3,\"completed\":true}]},"
            + "{\"number\":2,\"aired\":3,\"completed\":0,\"episodes\":[{\"number\":1,\"completed\":false}]}]}";

    @Test
    public void nextEpisodeAndWatchedOnes() {
        TraktClient.Progress p = TraktClient.parseProgress(PROGRESS, show());
        assertNotNull(p.next);
        assertEquals(2, p.next.season);
        assertEquals(1, p.next.episode);
        assertEquals("Despertar", p.next.episodeTitle);
        assertEquals(70523, p.next.tmdbId);
        assertEquals(3, p.watched.size());
        assertTrue(p.watched.contains(TraktClient.epKey(1, 3)));
        assertFalse(p.watched.contains(TraktClient.epKey(2, 1)));
    }

    @Test
    public void finishedSeriesOrNoAnswerOpensTheFirstEpisode() {
        TraktClient.Progress done = TraktClient.parseProgress("{\"next_episode\":null,\"seasons\":[]}", show());
        assertNull(done.next);
        TraktClient.Item t = TraktClient.openTarget(show(), done.next);
        assertEquals("episode", t.type);
        assertEquals(1, t.season);
        assertEquals(1, t.episode);
        assertEquals(70523, t.tmdbId);
        assertNull(TraktClient.parseProgress("garbage", show()).next);
        assertEquals(1, TraktClient.openTarget(show(), null).episode);
        TraktClient.Item next = TraktClient.parseProgress(PROGRESS, show()).next;
        assertEquals(next, TraktClient.openTarget(show(), next));
    }

    @Test
    public void findsTheTraktIdOfASeries() {
        assertEquals(1390, TraktClient.parseSearchShowId("[{\"type\":\"show\",\"show\":{\"ids\":{\"trakt\":1390}}}]"));
        assertEquals(0, TraktClient.parseSearchShowId("[]"));
        assertEquals(0, TraktClient.parseSearchShowId("nope"));
    }

    private static final String OWN = "[{\"name\":\"Para el finde\",\"ids\":{\"trakt\":11,\"slug\":\"para-el-finde\"},"
            + "\"user\":{\"ids\":{\"slug\":\"stefan\"}}},{\"name\":\"\",\"ids\":{\"trakt\":12}}]";
    private static final String LIKED = "[{\"liked_at\":\"2026-01-01T00:00:00.000Z\",\"type\":\"list\","
            + "\"list\":{\"name\":\"Terror\",\"ids\":{\"trakt\":77},\"user\":{\"ids\":{\"slug\":\"otro\"}}}}]";

    @Test
    public void parsesOwnAndLikedLists() {
        List<TraktClient.TraktList> own = TraktClient.parseLists(OWN);
        assertEquals(1, own.size());
        assertEquals("Para el finde", own.get(0).name);
        assertEquals(11, own.get(0).id);
        assertEquals("stefan", own.get(0).user);
        List<TraktClient.TraktList> liked = TraktClient.parseLists(LIKED);
        assertEquals("Terror", liked.get(0).name);
        assertEquals(77, liked.get(0).id);
        assertEquals("otro", liked.get(0).user);
        assertTrue(TraktClient.parseLists("x").isEmpty());
    }

    private static final String ITEMS = "[{\"rank\":1,\"listed_at\":\"2026-02-01T00:00:00.000Z\",\"type\":\"movie\","
            + "\"movie\":{\"title\":\"Alien\",\"year\":1979,\"ids\":{\"trakt\":1,\"tmdb\":348}}},"
            + "{\"rank\":2,\"type\":\"show\",\"show\":{\"title\":\"Dark\",\"year\":2017,\"ids\":{\"trakt\":1234,\"tmdb\":70523}}},"
            + "{\"rank\":3,\"type\":\"person\",\"person\":{\"name\":\"X\"}}]";

    @Test
    public void parsesListItemsAndTheirKind() {
        List<TraktClient.Item> items = TraktClient.parseListItems(ITEMS);
        assertEquals(2, items.size());
        assertEquals("movie", items.get(0).type);
        assertEquals(348, items.get(0).tmdbId);
        assertEquals("show", items.get(1).type);
        assertEquals(1234, items.get(1).showTraktId);
        assertEquals("mixed", TraktClient.listKind(items));
        assertEquals("movie", TraktClient.listKind(items.subList(0, 1)));
        assertEquals("series", TraktClient.listKind(items.subList(1, 2)));
    }

    @Test
    public void filtersListsByNameIgnoringCase() {
        List<TraktClient.TraktList> all = TraktClient.parseLists(OWN);
        all.addAll(TraktClient.parseLists(LIKED));
        assertEquals(2, TraktClient.filterLists(all, "").size());
        assertEquals(2, TraktClient.filterLists(all, null).size());
        List<TraktClient.TraktList> one = TraktClient.filterLists(all, " terror , otra");
        assertEquals(1, one.size());
        assertEquals("Terror", one.get(0).name);
        assertTrue(TraktClient.filterLists(all, "nada").isEmpty());
    }

    @Test
    public void listsSurviveTheCache() {
        List<TraktClient.TraktList> lists = TraktClient.parseLists(OWN);
        lists.get(0).items = TraktClient.parseListItems(ITEMS);
        List<TraktClient.TraktList> back = TraktClient.deserializeLists(TraktClient.serializeLists(lists));
        assertEquals(1, back.size());
        assertEquals(11, back.get(0).id);
        assertEquals("stefan", back.get(0).user);
        assertEquals(2, back.get(0).items.size());
        assertEquals("Dark", back.get(0).items.get(1).title);
        assertTrue(TraktClient.deserializeLists("").isEmpty());
        assertTrue(new ArrayList<>(TraktClient.deserializeLists("[]")).isEmpty());
    }

    @Test
    public void parsesTmdbSeriesAndSeason() {
        Tmdb.Series s = Tmdb.parseSeries("{\"backdrop_path\":\"/b.jpg\",\"seasons\":[{\"season_number\":0},"
                + "{\"season_number\":1},{\"season_number\":2}]}");
        assertEquals("https://image.tmdb.org/t/p/w780/b.jpg", s.backdrop);
        assertEquals(2, s.seasons.size());
        assertEquals(1, (int) s.seasons.get(0));
        List<Tmdb.Episode> eps = Tmdb.parseSeason("{\"episodes\":[{\"episode_number\":1,\"name\":\"Secretos\",\"still_path\":\"/s.jpg\"},"
                + "{\"episode_number\":2,\"name\":\"Episodio 2\",\"still_path\":null}]}");
        assertEquals(2, eps.size());
        assertEquals("E1 · Secretos", Tmdb.episodeLabel(eps.get(0)));
        assertEquals("https://image.tmdb.org/t/p/w780/s.jpg", eps.get(0).still);
        assertEquals("E2 · Episodio 2", Tmdb.episodeLabel(eps.get(1)));
        assertNull(eps.get(1).still);
    }
}
