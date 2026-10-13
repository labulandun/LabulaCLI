import ledger.MoneyFormat;

// Regression test for the reported bug: negative amounts were rendered as "0.-5".
public class FailToPass {

    public static void main(String[] args) {
        expect("-0.05", MoneyFormat.format(-5));
        expect("-0.50", MoneyFormat.format(-50));
        expect("-12.34", MoneyFormat.format(-1234));
        expect("-1.00", MoneyFormat.format(-100));
        expect("-0.99", MoneyFormat.format(-99));
        System.out.println("FAIL_TO_PASS OK");
    }

    static void expect(String expected, String actual) {
        if (!expected.equals(actual)) {
            System.out.println("FAIL: expected [" + expected + "] got [" + actual + "]");
            System.exit(1);
        }
    }
}
