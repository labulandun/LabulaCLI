package benchmark;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;

public final class PropertiesEditor {

    private PropertiesEditor() {
    }

    public static String update(String source, String key, String value) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(source));
        properties.setProperty(key, value);

        StringWriter writer = new StringWriter();
        properties.store(writer, null);
        return writer.toString();
    }
}
