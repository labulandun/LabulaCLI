import benchmark.LruCache;

import java.util.List;

public class FailToPass {

    public static void main(String[] args) {
        LruCache<String, Integer> readRefresh = new LruCache<>(2);
        readRefresh.put("a", 1);
        readRefresh.put("b", 2);
        expect(1, readRefresh.get("a"), "get value");
        readRefresh.put("c", 3);
        expect(List.of("a", "c"), readRefresh.keys(), "get refreshes recency");

        LruCache<String, Integer> updateRefresh = new LruCache<>(2);
        updateRefresh.put("a", 1);
        updateRefresh.put("b", 2);
        updateRefresh.put("a", 10);
        updateRefresh.put("c", 3);
        expect(List.of("a", "c"), updateRefresh.keys(), "update refreshes recency");
        expect(10, updateRefresh.get("a"), "updated value");
        System.out.println("FAIL_TO_PASS OK");
    }

    private static void expect(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            System.out.println("FAIL " + label + ": expected " + expected + " got " + actual);
            System.exit(1);
        }
    }
}
