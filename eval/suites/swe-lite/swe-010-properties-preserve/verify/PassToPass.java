import benchmark.PropertiesEditor;

import java.io.StringReader;
import java.util.Properties;

public class PassToPass {

    public static void main(String[] args) throws Exception {
        Properties parsed = parse(PropertiesEditor.update(
                "name=demo\nport=8080\n", "port", "9090"));
        expect("demo", parsed.getProperty("name"));
        expect("9090", parsed.getProperty("port"));

        Properties added = parse(PropertiesEditor.update("", "mode", "prod"));
        expect("prod", added.getProperty("mode"));
        System.out.println("PASS_TO_PASS OK");
    }

    private static Properties parse(String text) throws Exception {
        Properties properties = new Properties();
        properties.load(new StringReader(text));
        return properties;
    }

    private static void expect(String expected, String actual) {
        if (!expected.equals(actual)) {
            System.out.println("FAIL: expected " + expected + " got " + actual);
            System.exit(1);
        }
    }
}
