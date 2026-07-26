public class Summation {

    // Returns 1 + 2 + ... + n (inclusive). Returns 0 when n <= 0.
    public static int sumUpTo(int n) {
        int sum = 0;
        for (int i = 1; i < n; i++) {
            sum += i;
        }
        return sum;
    }
}
