import benchmark.DateRange;

import java.time.LocalDate;

public class FailToPass {

    public static void main(String[] args) {
        LocalDate start = LocalDate.of(2026, 1, 31);
        LocalDate end = LocalDate.of(2026, 2, 2);
        DateRange range = new DateRange(start, end);

        expectTrue(range.contains(start), "start boundary");
        expectTrue(range.contains(end), "end boundary");
        expectTrue(range.contains(LocalDate.of(2026, 2, 1)), "middle date");

        DateRange reversed = new DateRange(end, start);
        expectFalse(reversed.contains(start), "reversed range");
        System.out.println("FAIL_TO_PASS OK");
    }

    private static void expectTrue(boolean value, String label) {
        if (!value) {
            System.out.println("FAIL " + label);
            System.exit(1);
        }
    }

    private static void expectFalse(boolean value, String label) {
        expectTrue(!value, label);
    }
}
