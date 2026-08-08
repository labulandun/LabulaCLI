package com.paicli.runtime;

import java.util.concurrent.Callable;

public final class CancellationContext {
    private static final InheritableThreadLocal<CancellationToken> LOCAL = new InheritableThreadLocal<>();

    private CancellationContext() {
    }

    public static CancellationToken startRun() {
        return startRun(ResilienceConfig.current().agentDeadlineMillis());
    }

    public static CancellationToken startRun(long timeoutMillis) {
        CancellationToken token = new CancellationToken(RunDeadline.afterMillis(timeoutMillis));
        LOCAL.set(token);
        return token;
    }

    /** Ensures direct Agent/Plan/Team calls also receive a root deadline. */
    public static RunLease openRun() {
        CancellationToken existing = current();
        boolean owner = existing == null;
        CancellationToken token = owner ? startRun() : existing;
        return new RunLease(token, owner, bind(token));
    }

    public static CancellationToken current() {
        return LOCAL.get();
    }

    public static boolean isCancelled() {
        CancellationToken token = current();
        return token != null && token.isCancelled();
    }

    public static boolean isDeadlineExceeded() {
        CancellationToken token = current();
        return token != null && token.isDeadlineExceeded();
    }

    public static String stopMessage(String target) {
        String normalized = target == null || target.isBlank() ? "当前任务" : target;
        return isDeadlineExceeded()
                ? "⏱️ " + normalized + "已达到全链路 Deadline。"
                : "⏹️ 已取消" + normalized + "。";
    }

    public static long remainingMillis(long localCapMillis, long reserveMillis) {
        if (localCapMillis <= 0) {
            return 0;
        }
        CancellationToken token = current();
        if (token == null) {
            return localCapMillis;
        }
        if (token.isCancelled()) {
            return 0;
        }
        return token.deadline().boundedMillis(localCapMillis, reserveMillis);
    }

    public static CancellationToken childToken(long maximumMillis) {
        CancellationToken parent = current();
        return parent == null
                ? new CancellationToken(RunDeadline.afterMillis(maximumMillis))
                : parent.child(maximumMillis);
    }

    public static Scope bind(CancellationToken token) {
        CancellationToken previous = LOCAL.get();
        if (token == null) {
            LOCAL.remove();
            return new Scope(null, previous, CancellationToken.Registration.NO_OP);
        }
        LOCAL.set(token);
        Thread boundThread = Thread.currentThread();
        CancellationToken.Registration interruptRegistration = token.onCancel(boundThread::interrupt);
        return new Scope(token, previous, interruptRegistration);
    }

    public static <T> Callable<T> wrap(Callable<T> task) {
        CancellationToken captured = current();
        return () -> {
            try (Scope ignored = bind(captured)) {
                return task.call();
            }
        };
    }

    public static Runnable wrap(Runnable task) {
        CancellationToken captured = current();
        return () -> {
            try (Scope ignored = bind(captured)) {
                task.run();
            }
        };
    }

    public static void clear(CancellationToken token) {
        boolean ownedByCurrentThread = LOCAL.get() == token;
        if (ownedByCurrentThread) {
            LOCAL.remove();
        }
        if (token != null) {
            token.close();
        }
        if (ownedByCurrentThread && token != null && token.isCancelled()
                && Thread.currentThread().isInterrupted()) {
            Thread.interrupted();
        }
    }

    public static final class Scope implements AutoCloseable {
        private final CancellationToken token;
        private final CancellationToken previous;
        private final CancellationToken.Registration interruptRegistration;
        private boolean closed;

        private Scope(CancellationToken token,
                      CancellationToken previous,
                      CancellationToken.Registration interruptRegistration) {
            this.token = token;
            this.previous = previous;
            this.interruptRegistration = interruptRegistration;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            interruptRegistration.close();
            if (token != null && token != previous && token.isCancelled()
                    && Thread.currentThread().isInterrupted()) {
                Thread.interrupted();
            }
            if (previous == null) {
                LOCAL.remove();
            } else {
                LOCAL.set(previous);
            }
        }
    }

    public static final class RunLease implements AutoCloseable {
        private final CancellationToken token;
        private final boolean owner;
        private final Scope scope;
        private boolean closed;

        private RunLease(CancellationToken token, boolean owner, Scope scope) {
            this.token = token;
            this.owner = owner;
            this.scope = scope;
        }

        public CancellationToken token() {
            return token;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            scope.close();
            if (owner) {
                clear(token);
            }
        }
    }
}
