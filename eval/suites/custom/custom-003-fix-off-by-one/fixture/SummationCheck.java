public class SummationCheck {

    public static void main(String[] args) {
        expect(0, Summation.sumUpTo(0));
        expect(0, Summation.sumUpTo(-3));
        expect(1, Summation.sumUpTo(1));
        expect(15, Summation.sumUpTo(5));
        expect(55, Summation.sumUpTo(10));
        expect(5050, Summation.sumUpTo(100));
        System.out.println("ALL CHECKS PASSED");
    }

    static void expect(int expected, int actual) {
        if (expected != actual) {
            System.out.println("FAIL: expected " + expected + " got " + actual);
            System.exit(1);
        }
    }
}
