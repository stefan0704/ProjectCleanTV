package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TmdbTest {
    @Test
    public void minutesLeft() {
        assertEquals(21, Tmdb.minutesLeft(60, 65));
        assertEquals(0, Tmdb.minutesLeft(0, 40));
        assertEquals(0, Tmdb.minutesLeft(100, 100));
        assertEquals(100, Tmdb.minutesLeft(100, 0));
    }

    @Test
    public void picksSpanishThenEnglishBackdrop() {
        String body = "{\"backdrops\":[{\"iso_639_1\":null,\"file_path\":\"/n.jpg\",\"vote_average\":9},"
                + "{\"iso_639_1\":\"en\",\"file_path\":\"/en1.jpg\",\"vote_average\":5},"
                + "{\"iso_639_1\":\"en\",\"file_path\":\"/en2.jpg\",\"vote_average\":7},"
                + "{\"iso_639_1\":\"es\",\"file_path\":\"/es1.jpg\",\"vote_average\":3},"
                + "{\"iso_639_1\":\"es\",\"file_path\":\"/es2.jpg\",\"vote_average\":4}]}";
        assertEquals("/es2.jpg", Tmdb.pickTitledBackdrop(body));
        String en = "{\"backdrops\":[{\"iso_639_1\":null,\"file_path\":\"/n.jpg\"},"
                + "{\"iso_639_1\":\"en\",\"file_path\":\"/en1.jpg\",\"vote_average\":5},"
                + "{\"iso_639_1\":\"en\",\"file_path\":\"/en2.jpg\",\"vote_average\":7}]}";
        assertEquals("/en2.jpg", Tmdb.pickTitledBackdrop(en));
        assertEquals("", Tmdb.pickTitledBackdrop("{\"backdrops\":[{\"iso_639_1\":null,\"file_path\":\"/n.jpg\"}]}"));
        assertEquals("", Tmdb.pickTitledBackdrop("{}"));
    }
}
