public class GreetApp {

    // TODO: when shout is true, return the greeting upper-cased.
    public static String render(String name, boolean shout) {
        return "Hello, " + name + "!";
    }

    public static void main(String[] args) {
        String name = args.length > 0 ? args[0] : "world";
        System.out.println(render(name, false));
    }
}
