package benchmark;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LruCache<K, V> {

    private final int capacity;
    private final Map<K, V> values = new LinkedHashMap<>();

    public LruCache(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative");
        }
        this.capacity = capacity;
    }

    public V get(K key) {
        return values.get(key);
    }

    public void put(K key, V value) {
        values.put(key, value);
        if (values.size() > capacity) {
            K oldest = values.keySet().iterator().next();
            values.remove(oldest);
        }
    }

    public int size() {
        return values.size();
    }

    public List<K> keys() {
        return List.copyOf(values.keySet());
    }
}
