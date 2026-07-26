package com.paicli.eval;

/**
 * 一次 case 运行的聚合工件（E1 子集：状态 + 回答 + trace 关联 + 工作区变更统计）。
 * E2 会补充 spans.jsonl 解析出的 llm/tool 明细，见 docs/evaluation-system-design.md §3.3。
 */
public record RunArtifact(String caseId,
                          int runIndex,
                          String status,
                          String finalAnswer,
                          String error,
                          String traceId,
                          String rootObservationId,
                          long wallTimeMs,
                          int filesChanged,
                          int insertions,
                          int deletions) {
}
