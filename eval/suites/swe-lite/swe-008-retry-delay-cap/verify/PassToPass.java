import benchmark.RetryPolicy;

public class PassToPass {

    public static void main(String[] args) {
        expect(100, RetryPolicy.delayMillis(0, 100, 10_000));
        expect(200, RetryPolicy.delayMillis(1, 100, 10_000));
        expect(400, RetryPolicy.delayMillis(2, 100, 10_000));
        expect(800, RetryPolicy.delayMillis(3, 100, 800));
        expect(0, RetryPolicy.delayMillis(5, 0, 800));
        System.out.println("PASS_TO_PASS OK");
    }

    private static void expect(long expected, long actual) {
        if (expected != actual) {
            System.out.println("FAIL: expected " + expected + " got " + actual);
            System.exit(1);
        }
    }
}
