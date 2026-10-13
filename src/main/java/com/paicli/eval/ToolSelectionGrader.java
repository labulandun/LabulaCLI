package com.paicli.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 工具选择断言（tool-golden suite 用，数据来自 spans.jsonl 的 tool span 序列）。
 * 参数：
 * - expected（必填）：可接受的工具名数组
 * - mode：first_search（默认，首个检索类工具须在 expected 内）/ first（首个工具）/ any（调用过任一 expected）
 * - name：score 名，默认 tool_selection
 */
public final class ToolSelectionGrader implements Grader {

    /** 与 AGENTS.md「代码库理解默认走实时探索」口径一致的检索类工具集合。 */
    static final Set<String> SEARCH_TOOLS = Set.of("glob_files", "grep_code", "search_code", "list_dir");

    private final GraderSpec graderSpec;

    public ToolSelectionGrader(GraderSpec graderSpec) {
        this.graderSpec = graderSpec;
    }

    @Override
    public List<Score> grade(CaseSpec spec, RunArtifact artifact, Path workspace) {
        List<String> expected = expectedTools();
        if (expected.isEmpty()) {
            throw new IllegalArgumentException("tool_selection grader 缺少 expected (case " + spec.id() + ")");
        }
        String scoreName = graderSpec.text("name", "tool_selection");
        List<String> called = artifact.tools().stream().map(RunArtifact.ToolCallStat::name).toList();
        if (called.isEmpty()) {
            return List.of(Score.bool(scoreName, false, "没有 tool span（OTel 未生效或未调用任何工具）"));
        }
        String mode = graderSpec.text("mode", "first_search");
        return switch (mode) {
            case "first_search" -> {
                String firstSearch = called.stream().filter(SEARCH_TOOLS::contains).findFirst().orElse(null);
                boolean pass = firstSearch != null && expected.contains(firstSearch);
                yield List.of(Score.bool(scoreName, pass,
                        "首个检索类工具=" + firstSearch + " 期望=" + expected + " 序列=" + called));
            }
            case "first" -> {
                boolean pass = expected.contains(called.get(0));
                yield List.of(Score.bool(scoreName, pass,
                        "首个工具=" + called.get(0) + " 期望=" + expected));
            }
            case "any" -> {
                boolean pass = called.stream().anyMatch(expected::contains);
                yield List.of(Score.bool(scoreName, pass,
                        "期望之一被调用=" + pass + " 期望=" + expected + " 序列=" + called));
            }
            default -> throw new IllegalArgumentException("tool_selection 未知 mode: " + mode);
        };
    }

    private List<String> expectedTools() {
        List<String> expected = new ArrayList<>();
        JsonNode array = graderSpec.params().path("expected");
        if (array.isArray()) {
            for (JsonNode node : array) {
                if (!node.asText().isBlank()) {
                    expected.add(node.asText());
                }
            }
        }
        return expected;
    }
}
