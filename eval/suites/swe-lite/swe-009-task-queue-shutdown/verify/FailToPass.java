import benchmark.TaskQueue;

public class FailToPass {

    public static void main(String[] args) {
        TaskQueue queue = new TaskQueue();
        if (queue.poll() != null) {
            fail("empty poll must return null");
        }

        queue.shutdown();
        queue.shutdown();
        if (!queue.isShutdown()) {
            fail("shutdown state");
        }
        try {
            queue.enqueue("late");
            fail("enqueue after shutdown was accepted");
        } catch (IllegalStateException expected) {
            // expected
        }
        System.out.println("FAIL_TO_PASS OK");
    }

    private static void fail(String message) {
        System.out.println("FAIL: " + message);
        System.exit(1);
    }
}
