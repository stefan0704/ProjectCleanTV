package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Sets Trakt up from the PC, without typing on the remote. Protected by the DUMP permission, which only the adb shell
 * holds:
 * adb shell am broadcast -n com.gathin.tvhome/.TraktReceiver -a com.gathin.tvhome.TRAKT_CONFIG --es client_id ID --es client_secret SECRET
 * then read the code from logcat (tag TvHomeTrakt). TRAKT_STATUS logs the current state.
 * TMDB_CONFIG --es key KEY stores the TMDB API key used for the cards' art.
 * HIDDEN_CLEAR empties the set of titles hidden by hand from Explorar and Listas:
 * adb shell am broadcast -n com.gathin.tvhome/.TraktReceiver -a com.gathin.tvhome.HIDDEN_CLEAR
 */
public final class TraktReceiver extends BroadcastReceiver {
    static final String ACTION_CONFIG = "com.gathin.tvhome.TRAKT_CONFIG";
    static final String ACTION_STATUS = "com.gathin.tvhome.TRAKT_STATUS";
    static final String ACTION_TMDB = "com.gathin.tvhome.TMDB_CONFIG";
    static final String ACTION_HIDDEN_CLEAR = "com.gathin.tvhome.HIDDEN_CLEAR";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CONFIG.equals(action)) {
            String id = intent.getStringExtra("client_id");
            String secret = intent.getStringExtra("client_secret");
            if (id == null || id.trim().isEmpty()) {
                Log.w(TraktClient.TAG, "TRAKT_ERROR falta client_id");
                return;
            }
            SharedPreferences.Editor e = context.getSharedPreferences(TraktClient.PREFS, Context.MODE_PRIVATE).edit();
            e.putString("client_id", id.trim()).putString("client_secret", secret == null ? "" : secret.trim());
            e.remove("access_token").remove("refresh_token").remove("expires_at").apply();
            TraktClient.startDeviceFlow(context);
        } else if (ACTION_TMDB.equals(action)) {
            String key = intent.getStringExtra("key");
            if (key == null || key.trim().isEmpty()) return;
            Tmdb.setKey(context, key.trim());
            // Drop the cached list so the next Home resume fetches it again, now with art.
            context.getSharedPreferences(TraktClient.PREFS, Context.MODE_PRIVATE).edit().remove("cache").apply();
            Log.i(TraktClient.TAG, "TMDB_OK");
        } else if (ACTION_HIDDEN_CLEAR.equals(action)) {
            Hidden.clear(context);
            Log.i(TraktClient.TAG, "HIDDEN_CLEARED");
        } else if (ACTION_STATUS.equals(action)) {
            Log.i(TraktClient.TAG, "TRAKT_STATUS " + TraktClient.status(context));
        }
    }
}
