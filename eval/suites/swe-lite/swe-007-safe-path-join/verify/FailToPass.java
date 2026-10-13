import benchmark.SafePath;

import java.nio.file.Path;

public class FailToPass {

    public static void main(String[] args) {
        Path root = Path.of("workspace").toAbsolutePath().normalize();
        expectReject(root, "../outside.txt");
        expectReject(root, "sub/../../outside.txt");

        Path absolute = root.getRoot().resolve("outside.txt");
        expectReject(root, absolute.toString());
        System.out.println("FAIL_TO_PASS OK");
    }

    private static void expectReject(Path root, String candidate) {
        try {
            Path result = SafePath.resolve(root, candidate);
            System.out.println("FAIL: escaped path accepted: " + result);
            System.exit(1);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
