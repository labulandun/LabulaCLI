package ledger;

public final class MoneyFormat {

    private MoneyFormat() {
    }

    // Formats cents as a decimal string, e.g. 1234 -> "12.34", -5 -> "-0.05".
    public static String format(long cents) {
        long units = cents / 100;
        long remainder = cents % 100;
        return units + "." + (remainder < 10 ? "0" + remainder : String.valueOf(remainder));
    }
}
