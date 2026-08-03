import benchmark.SafePath;

import java.nio.file.Path;

public class PassToPass {

    public static void main(String[] args) {
        Path root = Path.of("workspace").toAbsolutePath().normalize();
        expect(root.resolve("src/Main.java"), SafePath.resolve(root, "src/Main.java"));
        expect(root.resolve("README.md"), SafePath.resolve(root, "src/../README.md"));
        expect(root, SafePath.resolve(root, "."));
        System.out.println("PASS_TO_PASS OK");
    }

    private static void expect(Path expected, Path actual) {
        if (!expected.normalize().equals(actual)) {
            System.out.println("FAIL: expected " + expected + " got " + actual);
            System.exit(1);
        }
        if (!actual.startsWith(expected.getParent()) && !actual.equals(expected)) {
            System.out.println("FAIL: unexpected root");
            System.exit(1);
        }
    }
}
