import ledger.Entry;
import ledger.Ledger;

// Regression test: entries with a null category used to blow up byCategory().
public class FailToPass {

    public static void main(String[] args) {
        Ledger ledger = new Ledger();
        ledger.add(new Entry("imported", null, 100));
        ledger.add(new Entry("coffee", "food", 350));
        ledger.add(new Entry("also imported", null, 200));

        expect(1, ledger.byCategory("food").size(), "filter by concrete category");
        expect(2, ledger.byCategory(null).size(), "filter by null category");
        expect(0, ledger.byCategory("missing").size(), "filter by unknown category");
        System.out.println("FAIL_TO_PASS OK");
    }

    static void expect(int expected, int actual, String label) {
        if (expected != actual) {
            System.out.println("FAIL " + label + ": expected " + expected + " got " + actual);
            System.exit(1);
        }
    }
}
