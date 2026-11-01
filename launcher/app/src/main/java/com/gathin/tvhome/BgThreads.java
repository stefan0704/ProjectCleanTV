package com.gathin.tvhome;

import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Background work (network, decoding) runs at THREAD_PRIORITY_BACKGROUND so it never competes with the UI thread. */
final class BgThreads {
    private BgThreads() {}

    static Thread thread(String name, Runnable task) {
        return new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, name);
    }

    static ExecutorService single(String name) {
        ThreadFactory factory = task -> thread(name, task);
        return Executors.newSingleThreadExecutor(factory);
    }
}
