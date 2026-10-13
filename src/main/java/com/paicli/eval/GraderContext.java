package com.paicli.eval;

import java.nio.file.Path;

/**
 * Optional run-scoped inputs for graders that need material outside RunArtifact.
 * Values must already be redacted before this context is created.
 */
public record GraderContext(Path caseRunDir, String workspaceDiff) {

    public GraderContext {
        workspaceDiff = workspaceDiff == null ? "" : workspaceDiff;
    }

    public static GraderContext empty() {
        return new GraderContext(null, "");
    }
}
