import java.util.ArrayDeque;
import java.util.Deque;

public class TaskQueue {

    private final Deque<String> tasks = new ArrayDeque<>();

    public void addTask(String task) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        tasks.addLast(task);
    }

    public String poll() {
        return tasks.pollFirst();
    }

    public int size() {
        return tasks.size();
    }
}
