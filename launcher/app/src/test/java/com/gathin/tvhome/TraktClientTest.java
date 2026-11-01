package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class TraktClientTest {
    private static final String PLAYBACK = "[{\"progress\":42.7,\"paused_at\":\"2026-10-27T21:00:00.000Z\",\"id\":1,"
            + "\"type\":\"movie\",\"movie\":{\"title\":\"Antes de ti\",\"year\":2016,\"ids\":{\"trakt\":5,"
            + "\"slug\":\"me-before-you-2016\",\"imdb\":\"tt2674426\",\"tmdb\":296096},"
            + "\"images\":{\"poster\":[\"media.trakt.tv/images/movies/000/000/005/posters/medium/a.jpg.webp\"],"
            + "\"fanart\":[\"walter-r2.trakt.tv/f.jpg\"]}}},"
            + "{\"progress\":10,\"paused_at\":\"2026-10-28T20:00:00.000Z\",\"id\":2,\"type\":\"episode\","
            + "\"episode\":{\"season\":1,\"number\":7,\"title\":\"Ganas o mueres\",\"ids\":{\"trakt\":99,\"tmdb\":63056}},"
            + "\"show\":{\"title\":\"Juego de tronos\",\"year\":2011,\"ids\":{\"trakt\":1390,\"tmdb\":1399},"
            + "\"images\":{\"poster\":[\"media.trakt.tv/images/shows/p.jpg\"]}}},"
            + "{\"type\":\"unknown\"}]";

    private static final String WATCHED = "[{\"plays\":8,\"last_watched_at\":\"2026-10-20T10:00:00.000Z\","
            + "\"show\":{\"title\":\"Dark\",\"year\":2017,\"ids\":{\"trakt\":1234,\"tmdb\":70523},"
            + "\"images\":{\"poster\":[\"media.trakt.tv/dark.jpg\"]}}},"
            + "{\"plays\":3,\"last_watched_at\":\"2026-10-29T10:00:00.000Z\","
            + "\"show\":{\"title\":\"Severance\",\"year\":2022,\"ids\":{\"trakt\":4321,\"tmdb\":95396}}},"
            + "{\"plays\":3,\"last_watched_at\":\"2026-10-10T10:00:00.000Z\","
            + "\"show\":{\"title\":\"Lost\",\"year\":2004,\"ids\":{\"trakt\":1,\"tmdb\":4607}}}]";

    @Test
    public void parsesPlayback() {
        List<TraktClient.Item> items = TraktClient.parsePlayback(PLAYBACK);
        assertEquals(2, items.size());
        TraktClient.Item m = items.get(0);
        assertEquals("movie", m.type);
        assertEquals("Antes de ti", m.title);
        assertEquals(2016, m.year);
        assertEquals(296096, m.tmdbId);
        assertEquals(43, m.progress);
        assertEquals("https://media.trakt.tv/images/movies/000/000/005/posters/medium/a.jpg.webp", m.posterUrl);
        TraktClient.Item e = items.get(1);
        assertEquals("episode", e.type);
        assertEquals("Juego de tronos", e.title);
        assertEquals(1399, e.tmdbId);
        assertEquals(1, e.season);
        assertEquals(7, e.episode);
        assertEquals("Ganas o mueres", e.episodeTitle);
        assertEquals(1390, e.showTraktId);
        assertEquals("2026-10-28T20:00:00.000Z", e.updatedAt);
    }

    @Test
    public void garbageGivesEmptyLists() {
        assertTrue(TraktClient.parsePlayback("not json").isEmpty());
        assertTrue(TraktClient.parsePlayback("{\"error\":1}").isEmpty());
        assertTrue(TraktClient.parsePlayback(null).isEmpty());
        assertTrue(TraktClient.parseWatchedShows("", 6).isEmpty());
    }

    @Test
    public void watchedShowsAreNewestFirstAndCapped() {
        List<TraktClient.Item> shows = TraktClient.parseWatchedShows(WATCHED, 2);
        assertEquals(2, shows.size());
        assertEquals("Severance", shows.get(0).title);
        assertEquals("Dark", shows.get(1).title);
        assertEquals("https://media.trakt.tv/dark.jpg", shows.get(1).posterUrl);
        assertNull(shows.get(0).posterUrl);
    }

    @Test
    public void parsesTheNextEpisode() {
        TraktClient.Item show = TraktClient.parseWatchedShows(WATCHED, 6).get(1);
        String body = "{\"aggregate\":{\"completed\":3},\"next_episode\":{\"season\":2,\"number\":1,"
                + "\"title\":\"Beginnings\",\"ids\":{\"trakt\":77}}}";
        TraktClient.Item n = TraktClient.parseNextEpisode(body, show);
        assertNotNull(n);
        assertEquals("Dark", n.title);
        assertEquals(2, n.season);
        assertEquals(1, n.episode);
        assertEquals("Beginnings", n.episodeTitle);
        assertEquals(0, n.progress);
        assertEquals(70523, n.tmdbId);
        assertEquals(show.updatedAt, n.updatedAt);
        assertNull(TraktClient.parseNextEpisode("{\"next_episode\":null}", show));
        assertNull(TraktClient.parseNextEpisode("{}", show));
    }

    @Test
    public void mergeDedupesAndSortsNewestFirst() {
        List<TraktClient.Item> playback = TraktClient.parsePlayback(PLAYBACK);
        TraktClient.Item got = new TraktClient.Item();
        got.type = "episode";
        got.title = "Juego de tronos";
        got.showTraktId = 1390;
        got.updatedAt = "2026-10-29T00:00:00.000Z";
        TraktClient.Item dark = new TraktClient.Item();
        dark.type = "episode";
        dark.title = "Dark";
        dark.showTraktId = 1234;
        dark.updatedAt = "2026-10-28T00:00:00.000Z";
        List<TraktClient.Item> next = new ArrayList<>();
        next.add(got);
        next.add(dark);
        List<TraktClient.Item> merged = TraktClient.merge(playback, next, 10);
        assertEquals(3, merged.size());
        assertEquals("2026-10-28T20:00:00.000Z", merged.get(0).updatedAt); // paused GoT episode, not the next one
        assertEquals("Dark", merged.get(1).title);
        assertEquals("Antes de ti", merged.get(2).title);
        assertEquals(1, TraktClient.merge(playback, next, 1).size());
    }

    @Test
    public void cacheRoundTrips() {
        List<TraktClient.Item> items = TraktClient.parsePlayback(PLAYBACK);
        items.get(1).episodeTitle = "Quote \" and \\ and ñ";
        List<TraktClient.Item> back = TraktClient.deserialize(TraktClient.serialize(items));
        assertEquals(2, back.size());
        assertEquals("Antes de ti", back.get(0).title);
        assertEquals(43, back.get(0).progress);
        assertEquals(items.get(0).posterUrl, back.get(0).posterUrl);
        assertEquals("episode", back.get(1).type);
        assertEquals("Quote \" and \\ and ñ", back.get(1).episodeTitle);
        assertEquals(7, back.get(1).episode);
        assertEquals(1390, back.get(1).showTraktId);
        assertTrue(TraktClient.deserialize("").isEmpty());
    }

    @Test
    public void parsesDeviceCodeAndToken() {
        TraktClient.DeviceCode d = TraktClient.parseDeviceCode("{\"device_code\":\"abc\",\"user_code\":\"5AF1B2C3\","
                + "\"verification_url\":\"https://trakt.tv/activate\",\"expires_in\":600,\"interval\":5}");
        assertNotNull(d);
        assertEquals("abc", d.deviceCode);
        assertEquals("5AF1B2C3", d.userCode);
        assertEquals(5, d.interval);
        assertNull(TraktClient.parseDeviceCode("{}"));

        TraktClient.Token t = TraktClient.parseToken("{\"access_token\":\"A\",\"token_type\":\"bearer\","
                + "\"expires_in\":7776000,\"refresh_token\":\"R\",\"scope\":\"public\",\"created_at\":1}", 1000);
        assertNotNull(t);
        assertEquals("A", t.access);
        assertEquals("R", t.refresh);
        assertEquals(1000 + 7776000_000L, t.expiresAtMs);
        assertNull(TraktClient.parseToken("{\"error\":\"x\"}", 0));
    }

    @Test
    public void jsonReaderHandlesEscapesAndRejectsJunk() {
        Object o = MiniJson.parse("{\"a\":\"x\\\"y\\u00f1\\n\",\"b\":[1,2.5,true,null],\"c\":{}}");
        assertEquals("x\"yñ\n", MiniJson.str(o, "a"));
        assertEquals(4, MiniJson.arr(MiniJson.get(o, "b")).size());
        assertNull(MiniJson.parse("{\"a\":"));
        assertNull(MiniJson.parse("[1,2] x"));
        assertEquals("\"a\\\"b\"", MiniJson.quote("a\"b"));
    }

    private static List<TraktClient.Item> numbered(int n) {
        List<TraktClient.Item> l = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            TraktClient.Item it = new TraktClient.Item();
            it.title = "T" + k;
            l.add(it);
        }
        return l;
    }

    @Test
    public void pickRandomIsStablePerSeedAndWithoutRepeats() {
        List<TraktClient.Item> all = numbered(40);
        List<TraktClient.Item> a = TraktClient.pickRandom(all, 10, 2026303);
        List<TraktClient.Item> b = TraktClient.pickRandom(all, 10, 2026303);
        assertEquals(10, a.size());
        assertEquals(10, new java.util.HashSet<>(a).size());
        for (int k = 0; k < 10; k++) assertTrue(a.get(k) == b.get(k));
        assertTrue(a.size() != TraktClient.pickRandom(all, 10, 2026304).size()
                || !a.equals(TraktClient.pickRandom(all, 10, 2026304)));
        assertEquals(40, all.size());
        assertEquals(3, TraktClient.pickRandom(numbered(3), 10, 1).size());
        assertEquals(0, TraktClient.pickRandom(null, 10, 1).size());
    }

    @Test
    public void parsesWatchlistAndKeepsShowsAtFirstEpisode() {
        String movies = "[{\"rank\":1,\"listed_at\":\"2026-10-01T10:00:00.000Z\",\"type\":\"movie\","
                + "\"movie\":{\"title\":\"Heat\",\"year\":1995,\"ids\":{\"trakt\":1,\"tmdb\":949}}}]";
        String shows = "[{\"rank\":1,\"type\":\"show\",\"show\":{\"title\":\"Dark\",\"year\":2017,\"ids\":{\"trakt\":2,\"tmdb\":70523}}}]";
        TraktClient.Item m = TraktClient.parseWatchlist(movies, false).get(0);
        assertEquals("movie", m.type);
        assertEquals(949, m.tmdbId);
        TraktClient.Item s = TraktClient.parseWatchlist(shows, true).get(0);
        assertEquals("show", s.type);
        assertEquals(1, s.season);
        assertEquals(1, s.episode);
        assertEquals(0, s.progress);
        assertEquals("show", TraktClient.deserialize(TraktClient.serialize(java.util.Collections.singletonList(s))).get(0).type);
        assertEquals("Dark · Serie", ContinueRow.caption(s));
    }
}
