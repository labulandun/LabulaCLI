import benchmark.RetryPolicy;

public class FailToPass {

    public static void main(String[] args) {
        expect(Long.MAX_VALUE, RetryPolicy.delayMillis(62, 4, Long.MAX_VALUE), "multiply overflow");
        expect(Long.MAX_VALUE, RetryPolicy.delayMillis(63, 1, Long.MAX_VALUE), "shift overflow");
        expect(5_000, RetryPolicy.delayMillis(100, 100, 5_000), "large attempt cap");
        expectReject(-1, 100, 1_000);
        expectReject(1, -1, 1_000);
        expectReject(1, 1, -1);
        System.out.println("FAIL_TO_PASS OK");
    }

    private static void expectReject(int attempt, long base, long max) {
        try {
            RetryPolicy.delayMillis(attempt, base, max);
            System.out.println("FAIL: invalid input accepted");
            System.exit(1);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void expect(long expected, long actual, String label) {
        if (expected != actual) {
            System.out.println("FAIL " + label + ": expected " + expected + " got " + actual);
            System.exit(1);
        }
    }
}
