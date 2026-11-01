package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Opens Kodi on demand and tells it what to play over its JSON-RPC socket (port 9090, local apps only, on by
 * default). Kodi is only started when the user picks something.
 */
final class KodiRpc {
    private KodiRpc() {}

    static final String PACKAGE = "org.xbmc.kodi";
    private static final String TAG = "TvHomeTrakt";
    private static final int PORT = 9090;
    private static final long WAIT_MS = 25_000, STEP_MS = 500;

    static void play(Context context, String pluginUrl) {
        final Context c = context.getApplicationContext();
        Intent launch = c.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (launch == null) {
            toast(c, "Kodi no está instalado");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(launch);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot start Kodi: " + e);
            return;
        }
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + WAIT_MS;
            while (System.currentTimeMillis() < deadline) {
                String pong = call(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"JSONRPC.Ping\"}");
                if (pong != null && pong.contains("pong")) {
                    waitForHome(deadline + WAIT_MS);
                    open(c, pluginUrl);
                    return;
                }
                try {
                    Thread.sleep(STEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            toast(c, "Kodi no responde");
        }, "kodi-play").start();
    }

    /**
     * A cold Kodi answers the ping before its skin is up, and a skin's intro video (Palantir's Estuary plays one)
     * then replaces whatever was opened. Wait until Home is showing with nothing playing, held for a second.
     */
    private static void waitForHome(long deadline) {
        String ask = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"XBMC.GetInfoBooleans\",\"params\":{\"booleans\":"
                + "[\"Window.IsActive(home)\",\"Player.HasMedia\"]}}";
        long readySince = 0;
        while (System.currentTimeMillis() < deadline) {
            String r = call(null, ask);
            boolean ready = r != null && r.contains("\"Window.IsActive(home)\":true")
                    && r.contains("\"Player.HasMedia\":false");
            long now = System.currentTimeMillis();
            if (!ready) readySince = 0;
            else if (readySince == 0) readySince = now;
            else if (now - readySince >= 1000) return;
            try {
                Thread.sleep(STEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void open(Context c, String pluginUrl) {
        String file = MiniJson.quote(pluginUrl);
        String r = call(c, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"Player.Open\",\"params\":{\"item\":{\"file\":"
                + file + "}}}");
        if (r != null && !r.contains("\"error\"")) return;
        Log.w(TAG, "Player.Open failed, trying ActivateWindow: " + r);
        call(c, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"GUI.ActivateWindow\",\"params\":{\"window\":\"videos\","
                + "\"parameters\":[" + file + ",\"return\"]}}");
    }

    /**
     * One request over Kodi's raw JSON-RPC socket (127.0.0.1:9090, "allow remote control from applications on this
     * system", on by default). Not HTTP: on this TV port 8080 belongs to a system service. Returns the response with
     * an "id", skipping notifications Kodi pushes on the same socket, or null when Kodi does not answer.
     */
    private static String call(Context c, String json) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream obj = new ByteArrayOutputStream();
            int depth = 0, total = 0;
            boolean inString = false, escaped = false;
            int b;
            while ((b = in.read()) != -1) {
                if (++total > 64 * 1024) return null;
                if (depth == 0 && b != '{') continue;
                obj.write(b);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (b == '\\') escaped = true;
                    else if (b == '"') inString = false;
                } else if (b == '"') {
                    inString = true;
                } else if (b == '{') {
                    depth++;
                } else if (b == '}' && --depth == 0) {
                    String text = new String(obj.toByteArray(), StandardCharsets.UTF_8);
                    if (text.contains("\"id\"")) return text;
                    obj.reset();   // a notification, not our answer
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void toast(Context c, String text) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(c, text, Toast.LENGTH_LONG).show());
    }
}
