package benchmark;

import java.util.ArrayDeque;
import java.util.Queue;

public final class TaskQueue {

    private final Queue<String> tasks = new ArrayDeque<>();
    private boolean shutdown;

    public void enqueue(String task) {
        tasks.add(task);
    }

    public String poll() {
        return tasks.remove();
    }

    public int size() {
        return tasks.size();
    }

    public void shutdown() {
        shutdown = true;
    }

    public boolean isShutdown() {
        return shutdown;
    }
}
