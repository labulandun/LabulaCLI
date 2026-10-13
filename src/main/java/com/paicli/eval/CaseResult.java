package com.paicli.eval;

import java.util.List;

/**
 * 一次 case 运行的最终结果。task_success 是复合分：
 * turn 正常结束且所有 BOOLEAN 分全部通过（见 docs/evaluation-system-design.md §3.2）。
 */
public record CaseResult(String caseId,
                         String suite,
                         int runIndex,
                         RunArtifact artifact,
                         List<Score> scores,
                         boolean taskSuccess,
                         String skipReason) {

    public static CaseResult of(CaseSpec spec, int runIndex, RunArtifact artifact, List<Score> scores) {
        List<Score> assertions = scores.stream()
                .filter(s -> s.dataType() == Score.DataType.BOOLEAN)
                .toList();
        boolean success = "completed".equals(artifact.status())
                && !assertions.isEmpty()
                && assertions.stream().allMatch(Score::passed);
        return new CaseResult(spec.id(), spec.suite(), runIndex, artifact, List.copyOf(scores), success, null);
    }

    public static CaseResult skipped(CaseSpec spec, String reason) {
        RunArtifact artifact = RunArtifact.failure(spec.id(), 1, "skipped", reason, 0);
        return new CaseResult(spec.id(), spec.suite(), 1, artifact, List.of(), false, reason);
    }

    public boolean executed() {
        return skipReason == null;
    }
}
