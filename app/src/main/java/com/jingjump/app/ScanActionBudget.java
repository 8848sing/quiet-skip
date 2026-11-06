package com.jingjump.app;

/**
 * Time-based click budget so persistent scanning can recover after a quiet
 * window without allowing an unbounded action rate.
 */
final class ScanActionBudget {
    private long windowMs;
    private int maxActions;
    private long windowStartedAt;
    private int actions;

    ScanActionBudget(long windowMs, int maxActions) {
        this.windowMs = windowMs;
        this.maxActions = maxActions;
    }

    void configure(long windowMs, int maxActions) {
        this.windowMs = Math.max(1_000L, windowMs);
        this.maxActions = Math.max(1, maxActions);
    }

    boolean hasCapacity(long now) {
        refresh(now);
        return actions < maxActions;
    }

    void recordAction(long now) {
        refresh(now);
        actions++;
    }

    void startNewWindow(long now) {
        windowStartedAt = now;
        actions = 0;
    }

    void reset() {
        windowStartedAt = 0L;
        actions = 0;
    }

    private void refresh(long now) {
        if (windowStartedAt == 0L || now - windowStartedAt >= windowMs) {
            startNewWindow(now);
        }
    }
}
