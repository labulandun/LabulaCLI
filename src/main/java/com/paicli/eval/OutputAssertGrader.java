package com.paicli.eval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 对 Agent 最终回答做断言。参数：contains / regex / not_contains（至少一个），name（默认 output_match）。
 * regex 按 DOTALL 匹配片段（Matcher.find）。
 */
public final class OutputAssertGrader implements Grader {

    private final GraderSpec graderSpec;

    public OutputAssertGrader(GraderSpec graderSpec) {
        this.graderSpec = graderSpec;
    }

    @Override
    public List<Score> grade(CaseSpec spec, RunArtifact artifact, Path workspace) {
        String answer = artifact.finalAnswer() == null ? "" : artifact.finalAnswer();
        List<String> failures = check(graderSpec, answer);
        String scoreName = graderSpec.text("name", "output_match");
        return List.of(Score.bool(scoreName, failures.isEmpty(),
                failures.isEmpty() ? "回答断言全部通过" : String.join("; ", failures)));
    }

    /** 与 FileAssertGrader 共用的文本断言逻辑。 */
    static List<String> check(GraderSpec spec, String text) {
        List<String> failures = new ArrayList<>();
        boolean hasRule = false;
        String contains = spec.text("contains", "");
        if (!contains.isBlank()) {
            hasRule = true;
            if (!text.contains(contains)) {
                failures.add("缺少内容: " + contains);
            }
        }
        String notContains = spec.text("not_contains", "");
        if (!notContains.isBlank()) {
            hasRule = true;
            if (text.contains(notContains)) {
                failures.add("包含不该出现的内容: " + notContains);
            }
        }
        String regex = spec.text("regex", "");
        if (!regex.isBlank()) {
            hasRule = true;
            if (!Pattern.compile(regex, Pattern.DOTALL).matcher(text).find()) {
                failures.add("regex 未命中: " + regex);
            }
        }
        if (!hasRule) {
            failures.add("断言缺少 contains / regex / not_contains");
        }
        return failures;
    }
}
