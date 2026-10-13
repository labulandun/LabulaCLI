import benchmark.CsvParser;

import java.util.List;

public class FailToPass {

    public static void main(String[] args) {
        expect(List.of("alpha", "beta,gamma", "delta"),
                CsvParser.parseLine("alpha,\"beta,gamma\",delta"));
        expect(List.of("x\"y", "z", ""),
                CsvParser.parseLine("\"x\"\"y\",z,"));
        System.out.println("FAIL_TO_PASS OK");
    }

    private static void expect(List<String> expected, List<String> actual) {
        if (!expected.equals(actual)) {
            System.out.println("FAIL: expected " + expected + " got " + actual);
            System.exit(1);
        }
    }
}
