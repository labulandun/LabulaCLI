public class RateLimiter {

    private static final int MAX_TOKENS = 10;
    private static final int REFILL_PER_SECOND = 2;

    private double tokens = MAX_TOKENS;
    private long lastRefillNanos = System.nanoTime();

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        tokens = Math.min(MAX_TOKENS, tokens + elapsedSeconds * REFILL_PER_SECOND);
        lastRefillNanos = now;
    }
}
