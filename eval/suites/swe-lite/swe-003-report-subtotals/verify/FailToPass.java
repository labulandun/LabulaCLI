import ledger.Entry;
import ledger.Ledger;
import ledger.Report;

// New behaviour: per-category subtotals between the detail lines and TOTAL.
public class FailToPass {

    public static void main(String[] args) {
        Ledger ledger = new Ledger();
        ledger.add(new Entry("coffee", "food", 350));
        ledger.add(new Entry("book", "study", 1200));
        ledger.add(new Entry("lunch", "food", 1650));

        String expected = String.join("\n",
                "coffee [food] 3.50",
                "book [study] 12.00",
                "lunch [food] 16.50",
                "SUBTOTAL",
                "  food 20.00",
                "  study 12.00",
                "TOTAL 32.00");
        String actual = new Report(ledger).render();
        if (!expected.equals(actual)) {
            System.out.println("FAIL: expected\n" + expected + "\n--- got ---\n" + actual);
            System.exit(1);
        }
        System.out.println("FAIL_TO_PASS OK");
    }
}
