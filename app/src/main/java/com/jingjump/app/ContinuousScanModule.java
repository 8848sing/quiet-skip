package com.jingjump.app;

import android.os.Handler;

/**
 * Independent per-app scan loop. It stops only when the active rule or app
 * changes, or when the accessibility service is interrupted.
 */
final class ContinuousScanModule {
    interface Callback {
        boolean shouldContinue();

        void scan();
    }

    private final Handler handler;
    private long intervalMs;
    private final Callback callback;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (!callback.shouldContinue()) {
                stop();
                return;
            }
            callback.scan();
            if (running) {
                handler.postDelayed(this, intervalMs);
            }
        }
    };

    ContinuousScanModule(Handler handler, long intervalMs, Callback callback) {
        this.handler = handler;
        this.intervalMs = intervalMs;
        this.callback = callback;
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        handler.postDelayed(tick, intervalMs);
    }

    void setIntervalMs(long intervalMs) {
        this.intervalMs = Math.max(120L, intervalMs);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    boolean isRunning() {
        return running;
    }
}
