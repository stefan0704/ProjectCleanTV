package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class WeatherTest {
    @Test
    public void parsesTheCurrentReading() {
        Weather.Reading r = Weather.parse("{\"latitude\":40.4,\"longitude\":-3.7,\"current_units\":{\"temperature_2m\":\"°C\"},"
                + "\"current\":{\"time\":\"2026-10-29T12:00\",\"interval\":900,\"temperature_2m\":18.6,\"weather_code\":3}}");
        assertNotNull(r);
        assertEquals(19, r.temperature);
        assertEquals(Weather.CLOUD, r.icon);
    }

    @Test
    public void roundsBelowZeroAndFormatsTheMinus() {
        Weather.Reading r = Weather.parse("{\"current\":{\"temperature_2m\":-2.4,\"weather_code\":71}}");
        assertNotNull(r);
        assertEquals(-2, r.temperature);
        assertEquals("−2°", Weather.format(r.temperature));
        assertEquals("18°", Weather.format(18));
    }

    @Test
    public void mapsWeatherCodesToIcons() {
        assertEquals(Weather.SUN, Weather.iconFor(0));
        assertEquals(Weather.SUN, Weather.iconFor(1));
        assertEquals(Weather.CLOUD, Weather.iconFor(2));
        assertEquals(Weather.CLOUD, Weather.iconFor(45));
        assertEquals(Weather.RAIN, Weather.iconFor(61));
        assertEquals(Weather.RAIN, Weather.iconFor(95));
    }

    @Test
    public void rejectsAnythingElse() {
        assertNull(Weather.parse(null));
        assertNull(Weather.parse(""));
        assertNull(Weather.parse("not json"));
        assertNull(Weather.parse("{\"error\":true,\"reason\":\"bad\"}"));
        assertNull(Weather.parse("{\"current\":{\"temperature_2m\":18.0}}"));
        assertNull(Weather.parse("{\"current\":{\"temperature_2m\":\"warm\",\"weather_code\":1}}"));
    }

    @Test
    public void buildsTheRequestFromTheCoordinates() {
        assertEquals("https://api.open-meteo.com/v1/forecast?latitude=40.4168&longitude=-3.7038&current=temperature_2m,weather_code",
                Weather.url("40.4168", "-3.7038"));
        assertNull(Weather.url("", ""));
        assertNull(Weather.url("abc", "1"));
        assertNull(Weather.url("91", "0"));
    }
}
