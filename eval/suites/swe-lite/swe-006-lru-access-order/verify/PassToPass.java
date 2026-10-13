import benchmark.LruCache;

import java.util.List;

public class PassToPass {

    public static void main(String[] args) {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        expect(List.of("b", "c"), cache.keys(), "basic eviction");
        expect(2, cache.size(), "size");
        expect(null, cache.get("missing"), "missing key");

        LruCache<String, Integer> zero = new LruCache<>(0);
        zero.put("ignored", 1);
        expect(0, zero.size(), "zero capacity");
        expect(List.of(), zero.keys(), "zero keys");
        System.out.println("PASS_TO_PASS OK");
    }

    private static void expect(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            System.out.println("FAIL " + label + ": expected " + expected + " got " + actual);
            System.exit(1);
        }
    }
}
