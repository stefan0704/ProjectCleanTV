package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The weather option: the current temperature from Open-Meteo for the configured latitude and longitude. The request
 * and the parsing are here, with no Android classes, so they can be tested; MainActivity runs {@link #fetch} on a
 * background thread every {@link #REFRESH_MS} while Home is on screen.
 */
final class Weather {
    private Weather() {}

    static final long REFRESH_MS = 30L * 60 * 1000;
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_BYTES = 16 * 1024;

    static final int SUN = 0, CLOUD = 1, RAIN = 2;

    static final class Reading {
        final int temperature;
        final int icon;

        Reading(int temperature, int icon) {
            this.temperature = temperature;
            this.icon = icon;
        }
    }

    /** The request URL, or null when the coordinates are not numbers in range. */
    static String url(String latitude, String longitude) {
        try {
            double lat = Double.parseDouble(latitude.trim());
            double lon = Double.parseDouble(longitude.trim());
            if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
            return String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code",
                    lat, lon);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?");

    /**
     * {"current":{"temperature_2m":18.4,"weather_code":3}} to a reading; null for anything else. Read by hand: the
     * answer is one flat object and org.json is not available to the unit tests.
     */
    static Reading parse(String body) {
        if (body == null) return null;
        int key = body.indexOf("\"current\"");
        if (key < 0) return null;
        int open = body.indexOf('{', key);
        int close = open < 0 ? -1 : body.indexOf('}', open);
        if (close < 0) return null;
        String current = body.substring(open, close);
        Double temp = number(current, "temperature_2m");
        Double code = number(current, "weather_code");
        if (temp == null || code == null || Math.abs(temp) > 90) return null;
        return new Reading((int) Math.round(temp), iconFor((int) Math.round(code)));
    }

    private static Double number(String object, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(" + NUMBER.pattern() + ")(?![\\w.\"])").matcher(object);
        if (!m.find()) return null;
        try {
            return Double.valueOf(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** WMO weather code: 0 and 1 clear, 2 and 3 cloud, fog 45/48, then drizzle, rain, snow and storms as rain. */
    static int iconFor(int code) {
        if (code <= 1) return SUN;
        if (code <= 48) return CLOUD;
        return RAIN;
    }

    /** "18°", with a true minus sign below zero. */
    static String format(int temperature) {
        return (temperature < 0 ? "−" : "") + Math.abs(temperature) + "°";
    }

    /** Blocking; call off the main thread. Null on any failure. */
    static Reading fetch(String latitude, String longitude) {
        String address = url(latitude, longitude);
        if (address == null) return null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > MAX_BYTES) return null;
                }
                return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
