package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PalantirLinkTest {
    @Test
    public void decodesAPayloadMadeWithTheReferenceAlgorithm() {
        // Generated with Python: b64encode(text).rstrip('='), rotated by int(0.75 * n), reversed.
        String payload = "NWZsV2cnAiOi42bpR3YhJye0XZ1JHVgojIlxmYhlXYsB1cpJCIscycllmdv12JgojI05WZ052bjJCIscyXz9WZklmd0";
        String text = PalantirLink.decode(payload);
        assertTrue(text, text.startsWith("{\"action\": 'selectvideos_'"));
        assertEquals("{\"action\": 'selectvideos_', \"content\": 'movies', \"isPlayable\": True}", text);
        assertEquals(payload, PalantirLink.encode(text));
    }

    @Test
    public void roundTripsForAnyLength() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            s.append((char) ('a' + i % 26)).append(i % 7 == 0 ? "ñ¿" : "");
            assertEquals(s.toString(), PalantirLink.decode(PalantirLink.encode(s.toString())));
        }
    }

    @Test
    public void buildsAnEpisodeLink() {
        TraktClient.Item it = new TraktClient.Item();
        it.type = "episode";
        it.title = "Juego de tronos";
        it.tmdbId = 1399;
        it.season = 1;
        it.episode = 7;
        it.episodeTitle = "Ganas o mueres";
        String url = PalantirLink.forItem(it);
        assertTrue(url.startsWith(PalantirLink.BASE));
        assertEquals("{\"action\": 'selectvideos_', \"content\": 'episodes', \"episode\": 7, \"isPlayable\": True, "
                        + "\"label\": 'Ganas o mueres', \"season\": 1, \"tmdb\": '1399', "
                        + "\"tvshowtitle\": 'Juego de tronos'}",
                PalantirLink.decode(url.substring(PalantirLink.BASE.length())));
    }

    @Test
    public void buildsAMovieLinkAndLabelsAnUntitledEpisode() {
        TraktClient.Item it = new TraktClient.Item();
        it.title = "Antes de ti";
        it.tmdbId = 296096;
        it.year = 2016;
        String url = PalantirLink.forItem(it);
        assertEquals("{\"action\": 'selectvideos_', \"content\": 'movies', \"fecha\": '2016-01-01', "
                        + "\"isPlayable\": True, \"label\": 'Antes de ti', \"title\": 'Antes de ti', \"tmdb\": '296096'}",
                PalantirLink.decode(url.substring(PalantirLink.BASE.length())));

        it.type = "episode";
        it.season = 2;
        it.episode = 3;
        assertTrue(PalantirLink.decode(PalantirLink.forItem(it).substring(PalantirLink.BASE.length()))
                .contains("\"label\": '2x3'"));
    }

    @Test
    public void quotesLikePythonRepr() {
        assertEquals("'abc'", PalantirLink.reprString("abc"));
        assertEquals("\"it's\"", PalantirLink.reprString("it's"));
        assertEquals("'it\\'s \"x\"'", PalantirLink.reprString("it's \"x\""));
        assertEquals("'a\\\\b'", PalantirLink.reprString("a\\b"));
    }
}
