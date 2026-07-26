public class ValidatorCheck {

    public static void main(String[] args) {
        expect(true, "a@b.co");
        expect(true, "user.name@example.org");
        expect(false, null);
        expect(false, "");
        expect(false, "plain-text");
        expect(false, "a@b");
        expect(false, "a@b@c.dom");
        expect(false, "@c.dom");
        expect(false, "a b@c.dom");
        System.out.println("ALL CHECKS PASSED");
    }

    static void expect(boolean expected, String input) {
        boolean actual = Validator.isValidEmail(input);
        if (expected != actual) {
            System.out.println("FAIL: isValidEmail(" + input + ") expected " + expected + " got " + actual);
            System.exit(1);
        }
    }
}
