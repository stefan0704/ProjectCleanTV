package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The quick settings panel the Home gear and the remote's Settings button (settings_key_panel) open: a full-height
 * sidebar sliding in from the right edge (the mirror of the Inputs sidebar), over Home or whatever app is playing.
 * Translucent and in a task of its own, so the app behind keeps running. Back, the button again or 15 s without a key
 * close it. The last row opens the TV's full Android settings.
 */
public final class QuickSettingsActivity extends Activity {
    private static final long IDLE_MS = 15_000;
    private static final long SLIDE_MS = 180;
    private final Runnable close = this::dismiss;
    private View panel, scrim;
    private boolean closing, slide, rtl;
    private Config cfg;
    private Palette pal;
    private float density;
    private AudioManager audio;
    private View volumeRow;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.load(this);
        UiFont.configure(this, cfg.uiFont);
        UiFont.install(this);
        pal = Palette.of(cfg);
        density = getResources().getDisplayMetrics().density;
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        setContentView(R.layout.activity_quick_settings);
        scrim = findViewById(R.id.scrim);
        scrim.setOnClickListener(v -> dismiss());
        panel = findViewById(R.id.panel);
        rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        slide = cfg.animations && (!cfg.reducedMotion || cfg.softMotion);
        // From the right edge (the Inputs sidebar takes the left); mirrored in RTL.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        lp.gravity = rtl ? Gravity.LEFT : Gravity.RIGHT;
        panel.setLayoutParams(lp);
        panel.setBackgroundColor(pal.popup(0xF2));   // 95%
        View edge = findViewById(R.id.edge);
        edge.setBackgroundColor(pal.border);
        ((FrameLayout.LayoutParams) edge.getLayoutParams()).gravity = rtl ? Gravity.RIGHT : Gravity.LEFT;
        TextView title = findViewById(R.id.overlayTitle);
        title.setTextColor(pal.primary);
        title.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);

        LinearLayout rows = findViewById(R.id.rows);
        View first = addRow(rows, R.drawable.ic_wifi, R.string.qs_wifi, wifiValue(),
                SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS));
        addRow(rows, R.drawable.ic_bluetooth, R.string.qs_bluetooth, getString(bluetoothOn() ? R.string.qs_on : R.string.qs_off),
                bluetoothIntent());
        volumeRow = addRow(rows, R.drawable.ic_volume, R.string.qs_volume, "", null);
        ProgressBar bar = volumeRow.findViewById(R.id.bar);
        bar.setVisibility(View.VISIBLE);
        bar.setProgressTintList(ColorStateList.valueOf(pal.accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(pal.divider));
        bar.setMax(audio != null ? audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : 1);
        refreshVolume();
        // The TV's own picture and sound pages where it has them (DISPLAY_SETTINGS answers with the picture page on
        // this firmware), else the standard ones.
        addRow(rows, R.drawable.ic_picture, R.string.qs_picture, null,
                SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS));
        addRow(rows, R.drawable.ic_volume, R.string.qs_sound, null, soundIntent());
        addRow(rows, R.drawable.ic_moon, R.string.qs_screensaver, null,
                new Intent().setComponent(new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")));
        // The TV's own sleep timer can only be set from inside its Settings process, so this one is the launcher's
        // (SleepTimer): OK steps Off, 15, 30, 60, 90, 120 minutes and the row stays open to show it.
        View sleep = addRow(rows, R.drawable.ic_moon, R.string.qs_sleep_timer, sleepValue(), null);
        sleep.setOnClickListener(v -> {
            int minutes = SleepTimer.cycle(this);
            if (minutes > 0 && !InputKeyService.isRunning()) {
                Toast.makeText(this, R.string.qs_sleep_timer_needs_helper, Toast.LENGTH_LONG).show();
            }
            ((TextView) sleep.findViewById(R.id.value)).setText(sleepValue());
        });
        View more = addRow(rows, R.drawable.ic_settings, R.string.qs_more, null,
                SystemSettings.intent(this, Settings.ACTION_SETTINGS));
        // Highlighted: a soft accent fill at rest, the usual focus frame when selected.
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(pal.focusFill);
        focused.setCornerRadius(13 * density);
        focused.setStroke(Math.round(2 * density), pal.accent);
        GradientDrawable rest = new GradientDrawable();
        rest.setColor((pal.accent & 0xFFFFFF) | 0x33000000);
        rest.setCornerRadius(13 * density);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, rest);
        more.setBackground(bg);
        ((LinearLayout.LayoutParams) more.getLayoutParams()).topMargin = Math.round(12 * density);
        first.requestFocus();

        if (slide) {
            panel.setTranslationX((rtl ? -1 : 1) * 320 * density);
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(SLIDE_MS).start();
            panel.animate().translationX(0f).setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator())
                    .withLayer().start();
        }
    }

    /** Slides back out to the edge when motion allows it; Back, the scrim, the idle timer and the button again. */
    private void dismiss() {
        if (closing || isFinishing()) return;
        closing = true;
        if (!slide || panel == null) {
            finish();
            return;
        }
        panel.animate().cancel();
        scrim.animate().cancel();
        scrim.animate().alpha(0f).setDuration(SLIDE_MS).start();
        panel.animate().translationX((rtl ? -1 : 1) * 320 * density).setDuration(SLIDE_MS)
                .setInterpolator(new DecelerateInterpolator()).withLayer()
                .withEndAction(() -> {
                    finish();
                    overridePendingTransition(0, 0);
                }).start();
    }

    @Override
    public void onBackPressed() {
        dismiss();
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        dismiss();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().postDelayed(close, IDLE_MS);
    }

    /** Any key restarts the idle timer; Left/Right on the Volume row change the volume without opening anything. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        View decor = getWindow().getDecorView();
        decor.removeCallbacks(close);
        decor.postDelayed(close, IDLE_MS);
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN && audio != null && volumeRow != null && volumeRow.isFocused()
                && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            int step = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, now + step)), 0);
            refreshVolume();
            return true;
        }
        boolean handled = super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE)) refreshVolume();
        return handled;
    }

    /** Anything else taking the screen (a settings page, Home, an app, a screensaver) closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        getWindow().getDecorView().removeCallbacks(close);
        finish();
        overridePendingTransition(0, 0);
    }

    private void refreshVolume() {
        if (audio == null || volumeRow == null) return;
        int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        ((TextView) volumeRow.findViewById(R.id.value)).setText(String.valueOf(now));
        ((ProgressBar) volumeRow.findViewById(R.id.bar)).setProgress(now);
    }

    /** This firmware's own Sound page (the activity answering SOUND_SETTINGS whose class is ".SoundActivity", not the
     *  AOSP "Display and sound" one), else the standard action. */
    private Intent soundIntent() {
        for (android.content.pm.ResolveInfo info : getPackageManager().queryIntentActivities(
                new Intent(Settings.ACTION_SOUND_SETTINGS), 0)) {
            if (info.activityInfo.name.endsWith(".SoundActivity")) {
                return new Intent().setComponent(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
            }
        }
        return SystemSettings.intent(this, Settings.ACTION_SOUND_SETTINGS);
    }

    /** Off, or the minutes left on the sleep timer. */
    private String sleepValue() {
        int minutes = SleepTimer.remainingMinutes(this);
        return minutes > 0 ? getString(R.string.qs_minutes, minutes) : getString(R.string.qs_off);
    }

    /** Bluetooth: the "Remotes and accessories" page, which on this Google TV settings app is the exported slice
     *  activity opened on the connected-devices slice; the TV's main settings where that activity isn't there. */
    private Intent bluetoothIntent() {
        Intent slice = new Intent().setComponent(new ComponentName("com.android.tv.settings",
                "com.android.tv.settings.slice.SliceActivity"))
                .putExtra("slice_uri", "content://com.android.tv.settings.accessories.sliceprovider/general");
        if (getPackageManager().resolveActivity(slice, 0) != null) return slice;
        return SystemSettings.intent(this, Settings.ACTION_SETTINGS);
    }

    /** Bluetooth on or off, read from the setting so no permission is needed. */
    private boolean bluetoothOn() {
        return Settings.Global.getInt(getContentResolver(), "bluetooth_on", 0) != 0;
    }

    /** Network name, "Ethernet" or "Desconectado". */
    private String wifiValue() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (ethernet) return getString(R.string.ethernet);
            if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) return ssid;
                }
                return getString(R.string.qs_connected);
            }
        } catch (Exception ignored) {
        }
        return getString(R.string.qs_disconnected);
    }

    private View addRow(LinearLayout rows, int icon, int title, String value, Intent target) {
        View row = getLayoutInflater().inflate(R.layout.item_quick, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView valueView = row.findViewById(R.id.value);
        if (value != null && !value.isEmpty()) valueView.setText(value);
        else if (value == null) valueView.setVisibility(View.GONE);
        if (target != null) {
            row.setOnClickListener(v -> {
                try {
                    startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException e) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
                finish();
            });
        }
        rows.addView(row);
        pal.skinTree(row, density);
        return row;
    }
}
