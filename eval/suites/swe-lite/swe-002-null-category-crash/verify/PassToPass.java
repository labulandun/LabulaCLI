import ledger.Entry;
import ledger.Ledger;
import ledger.MoneyFormat;
import ledger.Report;

// Behaviour that already worked and must keep working after the fix.
public class PassToPass {

    public static void main(String[] args) {
        Ledger ledger = new Ledger();
        ledger.add(new Entry("coffee", "food", 350));
        ledger.add(new Entry("lunch", "food", 1650));
        ledger.add(new Entry("book", "study", 1200));

        expectLong(3200, ledger.balanceCents());
        expectLong(2, ledger.byCategory("food").size());
        expectLong(1, ledger.byCategory("study").size());
        expectLong(3, ledger.entries().size());
        expect("12.34", MoneyFormat.format(1234));
        expect("coffee [food] 3.50\nlunch [food] 16.50\nbook [study] 12.00\nTOTAL 32.00",
                new Report(ledger).render());

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
