import ledger.Entry;
import ledger.Ledger;
import ledger.MoneyFormat;

// Everything the new report feature must not break.
public class PassToPass {

    public static void main(String[] args) {
        expect("12.34", MoneyFormat.format(1234));
        expect("0.05", MoneyFormat.format(5));
        expect("100.00", MoneyFormat.format(10000));

        Ledger ledger = new Ledger();
        ledger.add(new Entry("coffee", "food", 350));
        ledger.add(new Entry("book", "study", 1200));
        expectLong(1550, ledger.balanceCents());
        expectLong(2, ledger.entries().size());
        expectLong(1, ledger.byCategory("food").size());
        expectLong(0, ledger.byCategory("missing").size());

        try {
            ledger.add(null);
            System.out.println("FAIL: null entry should be rejected");
            System.exit(1);
        } catch (IllegalArgumentException expected) {
            // ok
        }
        System.out.println("PASS_TO_PASS OK");
    }

    static void expect(String expected, String actual) {
        if (!expected.equals(actual)) {
            System.out.println("FAIL: expected [" + expected + "] got [" + actual + "]");
            System.exit(1);
        }
    }

    static void expectLong(long expected, long actual) {
        if (expected != actual) {
            System.out.println("FAIL: expected " + expected + " got " + actual);
            System.exit(1);
        }
    }
}
