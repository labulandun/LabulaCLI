package benchmark;

public final class RetryPolicy {

    private RetryPolicy() {
    }

    public static long delayMillis(int attempt, long baseDelay, long maxDelay) {
        long multiplier = 1L << attempt;
        return Math.min(maxDelay, baseDelay * multiplier);
    }
}
