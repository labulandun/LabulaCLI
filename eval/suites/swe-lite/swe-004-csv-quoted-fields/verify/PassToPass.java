import benchmark.CsvParser;

import java.util.List;

public class PassToPass {

    public static void main(String[] args) {
        expect(List.of("a", "b", "c"), CsvParser.parseLine("a,b,c"));
        expect(List.of("a", "", "c"), CsvParser.parseLine("a,,c"));
        expect(List.of(""), CsvParser.parseLine(""));
        System.out.println("PASS_TO_PASS OK");
    }

    private static void expect(List<String> expected, List<String> actual) {
        if (!expected.equals(actual)) {
            System.out.println("FAIL: expected " + expected + " got " + actual);
            System.exit(1);
        }
    }
}
