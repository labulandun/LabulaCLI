package benchmark;

import java.nio.file.Path;

public final class SafePath {

    private SafePath() {
    }

    public static Path resolve(Path root, String candidate) {
        if (root == null || candidate == null) {
            throw new IllegalArgumentException("root and candidate are required");
        }
        return root.resolve(candidate).normalize();
    }
}
