package com.paicli.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceTest {

    @TempDir
    Path tempDir;

    private CaseSpec specWithFixture(Path fixtureDir) {
        return EvalFixtures.caseSpec("ws-case", "custom", fixtureDir.getParent(), fixtureDir);
    }

    @Test
    void provisionCopiesFixtureAndDiffTracksChanges() throws Exception {
        Path fixture = tempDir.resolve("fixture");
        Files.createDirectories(fixture.resolve("sub"));
        Files.writeString(fixture.resolve("keep.txt"), "keep\n", StandardCharsets.UTF_8);
        Files.writeString(fixture.resolve("edit.txt"), "old line\n", StandardCharsets.UTF_8);
        Files.writeString(fixture.resolve("sub").resolve("gone.txt"), "bye\n", StandardCharsets.UTF_8);

        Workspace workspace = Workspace.provision(specWithFixture(fixture), tempDir.resolve("ws"));
        try {
            assertTrue(Files.exists(workspace.root().resolve("keep.txt")));

            Files.writeString(workspace.root().resolve("edit.txt"), "new line\n", StandardCharsets.UTF_8);
            Files.writeString(workspace.root().resolve("created.txt"), "hello\nworld\n", StandardCharsets.UTF_8);
            Files.delete(workspace.root().resolve("sub").resolve("gone.txt"));

            Workspace.DiffStats diff = workspace.diff();
            assertEquals(3, diff.filesChanged());
            assertEquals(3, diff.insertions());
            assertEquals(2, diff.deletions());
            assertTrue(diff.patch().contains("created.txt"));
            assertTrue(diff.patch().contains("-old line"));
            assertTrue(diff.patch().contains("+new line"));
        } finally {
            workspace.deleteQuietly();
        }
    }

    @Test
    void emptyFixtureProducesEmptyBaselineAndDiff() throws Exception {
        CaseSpec spec = EvalFixtures.caseSpec("empty-case", "custom", tempDir, null);
        Workspace workspace = Workspace.provision(spec, tempDir.resolve("ws-empty"));
        try {
            Workspace.DiffStats before = workspace.diff();
            assertEquals(0, before.filesChanged());
        } finally {
            workspace.deleteQuietly();
        }
    }
}
