public class WordsCheck {

    public static void main(String[] args) {
        expect("world hello", Words.reverseWords("hello world"));
        expect("single", Words.reverseWords("single"));
        expect("c b a", Words.reverseWords("a b c"));
        expect("", Words.reverseWords(""));
        System.out.println("ALL CHECKS PASSED");
    }

    static void expect(String expected, String actual) {
        if (!expected.equals(actual)) {
            System.out.println("FAIL: expected [" + expected + "] got [" + actual + "]");
            System.exit(1);
        }
    }
}
