package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * The quick settings sleep timer. This firmware's own timer (Settings, Power and energy) can only be set from inside
 * its Settings process, so this one is the launcher's: an alarm that, when it fires, locks the screen through the
 * InputKeyService helper, which puts the TV to sleep. Cycles Off, 15, 30, 60, 90 and 120 minutes.
 */
final class SleepTimer {
    static final int[] MINUTES = {0, 15, 30, 60, 90, 120};
    private static final String PREFS = "sleep_timer";
    private static final String KEY_END = "end";
    private static final String KEY_MINUTES = "minutes";

    private SleepTimer() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static PendingIntent pending(Context c) {
        return PendingIntent.getBroadcast(c, 0, new Intent(c, SleepTimerReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Milliseconds left, 0 when the timer is off or has run out. */
    static long remainingMs(Context c) {
        return Math.max(0, prefs(c).getLong(KEY_END, 0) - System.currentTimeMillis());
    }

    /** Minutes to show: what is left, rounded up; 0 when off. */
    static int remainingMinutes(Context c) {
        return (int) ((remainingMs(c) + 59_999) / 60_000);
    }

    /** Moves to the next step (the last one goes back to off) and returns its minutes. */
    static int cycle(Context c) {
        int now = remainingMs(c) > 0 ? prefs(c).getInt(KEY_MINUTES, 0) : 0;
        int next = 0;
        for (int i = 0; i < MINUTES.length; i++) if (MINUTES[i] == now) next = MINUTES[(i + 1) % MINUTES.length];
        set(c, next);
        return next;
    }

    static void set(Context c, int minutes) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = pending(c);
        if (am != null) am.cancel(pi);
        SharedPreferences.Editor edit = prefs(c).edit();
        if (minutes <= 0 || am == null) {
            edit.putLong(KEY_END, 0).putInt(KEY_MINUTES, 0).apply();
            return;
        }
        long end = System.currentTimeMillis() + minutes * 60_000L;
        edit.putLong(KEY_END, end).putInt(KEY_MINUTES, minutes).apply();
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pi);
        } catch (SecurityException e) {   // Android 12+ without the exact alarm permission: a little late is fine
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pi);
        }
    }

    /** The alarm went off: clear the state and put the TV to sleep. */
    static void fire(Context c) {
        prefs(c).edit().putLong(KEY_END, 0).putInt(KEY_MINUTES, 0).apply();
        InputKeyService.sleepNow();
    }
}
