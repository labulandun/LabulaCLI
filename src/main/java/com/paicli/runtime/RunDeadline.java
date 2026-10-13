package com.paicli.runtime;

import java.util.concurrent.TimeUnit;

/** An absolute monotonic deadline shared by a run and narrowed by child operations. */
public final class RunDeadline {
    private static final RunDeadline UNBOUNDED = new RunDeadline(Long.MAX_VALUE);

    private final long deadlineNanos;

    private RunDeadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    public static RunDeadline unbounded() {
        return UNBOUNDED;
    }

    public static RunDeadline afterMillis(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            return new RunDeadline(System.nanoTime());
        }
        long duration = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long now = System.nanoTime();
        long deadline = now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
        return new RunDeadline(deadline);
    }

    public RunDeadline child(long maximumMillis) {
        RunDeadline local = afterMillis(maximumMillis);
        if (deadlineNanos == Long.MAX_VALUE) {
            return local;
        }
        return new RunDeadline(Math.min(deadlineNanos, local.deadlineNanos));
    }

    public boolean isExpired() {
        return deadlineNanos != Long.MAX_VALUE && remainingNanos() <= 0;
    }

    public long remainingMillis() {
        if (deadlineNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long remaining = remainingNanos();
        if (remaining <= 0) {
            return 0;
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
        return millis > 0 ? millis : 1;
    }

    public long boundedMillis(long localCapMillis, long reserveMillis) {
        long cap = Math.max(1, localCapMillis);
        if (deadlineNanos == Long.MAX_VALUE) {
            return cap;
        }
        long usable = Math.max(0, remainingMillis() - Math.max(0, reserveMillis));
        return Math.min(cap, usable);
    }

    long deadlineNanos() {
        return deadlineNanos;
    }

    private long remainingNanos() {
        return deadlineNanos - System.nanoTime();
    }
}
