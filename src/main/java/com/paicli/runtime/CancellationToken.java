package com.paicli.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class CancellationToken implements AutoCloseable {
    public enum Reason {
        NONE,
        USER,
        DEADLINE_EXCEEDED,
        PARENT_CANCELLED,
        INTERRUPTED
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        Registration NO_OP = () -> { };

        @Override
        void close();
    }

    private static final ScheduledThreadPoolExecutor DEADLINE_SCHEDULER = createDeadlineScheduler();

    private static ScheduledThreadPoolExecutor createDeadlineScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, r -> {
                Thread thread = new Thread(r, "paicli-run-deadline");
                thread.setDaemon(true);
                return thread;
            });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private final AtomicReference<Reason> reason = new AtomicReference<>(Reason.NONE);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final List<Runnable> cancellationListeners = new CopyOnWriteArrayList<>();
    private final RunDeadline deadline;
    private final ScheduledFuture<?> deadlineTask;
    private volatile Registration parentRegistration = Registration.NO_OP;

    public CancellationToken() {
        this(RunDeadline.unbounded());
    }

    public CancellationToken(RunDeadline deadline) {
        this.deadline = deadline == null ? RunDeadline.unbounded() : deadline;
        long remaining = this.deadline.remainingMillis();
        this.deadlineTask = remaining == Long.MAX_VALUE
                ? null
                : DEADLINE_SCHEDULER.schedule(
                        () -> cancel(Reason.DEADLINE_EXCEEDED), Math.max(0, remaining), TimeUnit.MILLISECONDS);
    }

    public void cancel() {
        cancel(Reason.USER);
    }

    public void timeout() {
        cancel(Reason.DEADLINE_EXCEEDED);
    }

    public boolean isCancelled() {
        refreshState();
        return reason.get() != Reason.NONE;
    }

    public boolean isDeadlineExceeded() {
        refreshState();
        return reason.get() == Reason.DEADLINE_EXCEEDED;
    }

    public Reason reason() {
        refreshState();
        return reason.get();
    }

    public RunDeadline deadline() {
        return deadline;
    }

    public long remainingMillis() {
        return deadline.remainingMillis();
    }

    public CancellationToken child(long maximumMillis) {
        CancellationToken child = new CancellationToken(deadline.child(maximumMillis));
        child.parentRegistration = onCancel(() -> {
            Reason parentReason = reason();
            child.cancel(parentReason == Reason.DEADLINE_EXCEEDED
                    ? Reason.DEADLINE_EXCEEDED
                    : Reason.PARENT_CANCELLED);
        });
        return child;
    }

    public Registration onCancel(Runnable listener) {
        if (listener == null) {
            return Registration.NO_OP;
        }
        if (isCancelled()) {
            safeRun(listener);
            return Registration.NO_OP;
        }
        if (closed.get()) {
            return Registration.NO_OP;
        }
        cancellationListeners.add(listener);
        if (isCancelled() && cancellationListeners.remove(listener)) {
            safeRun(listener);
            return Registration.NO_OP;
        }
        if (closed.get() && cancellationListeners.remove(listener)) {
            return Registration.NO_OP;
        }
        return () -> cancellationListeners.remove(listener);
    }

    private void cancel(Reason cancellationReason) {
        Reason normalized = cancellationReason == null || cancellationReason == Reason.NONE
                ? Reason.USER
                : cancellationReason;
        if (!reason.compareAndSet(Reason.NONE, normalized)) {
            return;
        }
        if (deadlineTask != null) {
            deadlineTask.cancel(false);
        }
        for (Runnable listener : cancellationListeners) {
            safeRun(listener);
        }
        cancellationListeners.clear();
    }

    private void refreshState() {
        if (reason.get() != Reason.NONE) {
            return;
        }
        if (deadline.isExpired()) {
            cancel(Reason.DEADLINE_EXCEEDED);
        } else if (Thread.currentThread().isInterrupted()) {
            cancel(Reason.INTERRUPTED);
        }
    }

    private static void safeRun(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException ignored) {
            // Cancellation must continue even if one resource cleanup callback fails.
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (deadlineTask != null) {
            deadlineTask.cancel(false);
        }
        parentRegistration.close();
        cancellationListeners.clear();
    }
}
