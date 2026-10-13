import benchmark.PropertiesEditor;

public class FailToPass {

    public static void main(String[] args) throws Exception {
        String source = "# application\nport=8080\nname=demo\n";
        expect("# application\nport=9090\nname=demo\n",
                PropertiesEditor.update(source, "port", "9090"), "replace");

        String missing = "# application\r\nname=demo\r\n";
        expect("# application\r\nname=demo\r\nmode=prod\r\n",
                PropertiesEditor.update(missing, "mode", "prod"), "append");
        System.out.println("FAIL_TO_PASS OK");
    }

    private static void expect(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            System.out.println("FAIL " + label + ": expected [" + expected + "] got [" + actual + "]");
            System.exit(1);
        }
    }
}
