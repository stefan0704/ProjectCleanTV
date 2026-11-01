package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The sleep timer's alarm (SleepTimer); not exported, only its own PendingIntent reaches it. */
public final class SleepTimerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SleepTimer.fire(context);
    }
}
