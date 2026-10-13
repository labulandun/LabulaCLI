public class GreetAppCheck {

    public static void main(String[] args) {
        expect("Hello, world!", GreetApp.render("world", false));
        expect("HELLO, WORLD!", GreetApp.render("world", true));
        expect("Hello, Ada!", GreetApp.render("Ada", false));
        expect("HELLO, ADA!", GreetApp.render("Ada", true));
        System.out.println("ALL CHECKS PASSED");
    }

    static void expect(String expected, String actual) {
        if (!expected.equals(actual)) {
            System.out.println("FAIL: expected [" + expected + "] got [" + actual + "]");
            System.exit(1);
        }
    }
}
