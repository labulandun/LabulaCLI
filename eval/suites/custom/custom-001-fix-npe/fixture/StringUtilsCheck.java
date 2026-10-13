public class StringUtilsCheck {

    public static void main(String[] args) {
        expect("", StringUtils.capitalize(null), "capitalize(null)");
        expect("", StringUtils.capitalize(""), "capitalize(empty)");
        expect("Hello", StringUtils.capitalize("hello"), "capitalize(hello)");
        expect("World", StringUtils.capitalize("World"), "capitalize(World)");
        expect("A", StringUtils.capitalize("a"), "capitalize(a)");
        System.out.println("ALL CHECKS PASSED");
    }

    static void expect(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            System.out.println("FAIL " + label + ": expected [" + expected + "] got [" + actual + "]");
            System.exit(1);
        }
    }
}
