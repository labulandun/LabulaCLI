public class FizzBuzzCheck {

    public static void main(String[] args) {
        expect("1", FizzBuzz.render(1));
        expect("2", FizzBuzz.render(2));
        expect("Fizz", FizzBuzz.render(3));
        expect("Buzz", FizzBuzz.render(5));
        expect("Fizz", FizzBuzz.render(9));
        expect("Buzz", FizzBuzz.render(20));
        expect("FizzBuzz", FizzBuzz.render(15));
        expect("FizzBuzz", FizzBuzz.render(30));
        expect("7", FizzBuzz.render(7));
        System.out.println("ALL CHECKS PASSED");
    }

    static void expect(String expected, String actual) {
        if (!expected.equals(actual)) {
            System.out.println("FAIL: expected [" + expected + "] got [" + actual + "]");
            System.exit(1);
        }
    }
}
