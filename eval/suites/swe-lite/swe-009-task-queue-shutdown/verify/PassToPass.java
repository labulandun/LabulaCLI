import benchmark.TaskQueue;

public class PassToPass {

    public static void main(String[] args) {
        TaskQueue queue = new TaskQueue();
        queue.enqueue("first");
        queue.enqueue("second");
        expect("first", queue.poll());
        expect("second", queue.poll());
        expect(0, queue.size());
        queue.shutdown();
        queue.shutdown();
        if (!queue.isShutdown()) {
            fail("shutdown state");
        }
        System.out.println("PASS_TO_PASS OK");
    }

    private static void expect(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            fail("expected " + expected + " got " + actual);
        }
    }

    private static void fail(String message) {
        System.out.println("FAIL: " + message);
        System.exit(1);
    }
}
