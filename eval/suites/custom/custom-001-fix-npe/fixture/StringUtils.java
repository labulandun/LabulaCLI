public class StringUtils {

    // Returns the input with its first letter upper-cased.
    public static String capitalize(String input) {
        return input.substring(0, 1).toUpperCase() + input.substring(1);
    }
}
