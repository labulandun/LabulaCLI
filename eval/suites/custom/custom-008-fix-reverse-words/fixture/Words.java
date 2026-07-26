public class Words {

    // Reverses the order of words. Words are separated by single spaces.
    public static String reverseWords(String sentence) {
        return new StringBuilder(sentence).reverse().toString();
    }
}
