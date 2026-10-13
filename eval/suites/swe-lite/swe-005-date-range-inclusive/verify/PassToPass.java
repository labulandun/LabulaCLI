import benchmark.DateRange;

import java.time.LocalDate;

public class PassToPass {

    public static void main(String[] args) {
        DateRange range = new DateRange(
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31));

        expectTrue(range.contains(LocalDate.of(2026, 3, 15)), "middle");
        expectFalse(range.contains(LocalDate.of(2026, 2, 28)), "before");
        expectFalse(range.contains(LocalDate.of(2026, 4, 1)), "after");
        expectFalse(range.contains(null), "null");
        expectFalse(new DateRange(null, LocalDate.now()).contains(LocalDate.now()), "null start");
        System.out.println("PASS_TO_PASS OK");
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
