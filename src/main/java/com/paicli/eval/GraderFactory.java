package com.paicli.eval;

/** grader type → 实现。未识别的 type 直接抛错，避免 case 拼错时静默跳过。 */
public final class GraderFactory {

    private GraderFactory() {
    }

    public static Grader create(GraderSpec spec, CommandRunner runner) {
        return create(spec, runner, null);
    }

    public static Grader create(GraderSpec spec, CommandRunner runner,
                                LlmJudgeGrader.Dependencies judgeDependencies) {
        return switch (spec.type()) {
            case "command" -> new CommandGrader(spec, runner);
            case "output_assert" -> new OutputAssertGrader(spec);
            case "file_assert" -> new FileAssertGrader(spec);
            case "tool_selection" -> new ToolSelectionGrader(spec);
            case "safety" -> new SafetyGrader(spec);
            case "llm_judge" -> createJudge(spec, judgeDependencies);
            default -> throw new IllegalArgumentException(
                    "未知 grader type: " + spec.type()
                            + "（支持 command / output_assert / file_assert / tool_selection / safety / llm_judge）");
        };
    }

    private static Grader createJudge(GraderSpec spec,
                                      LlmJudgeGrader.Dependencies dependencies) {
        if (dependencies == null) {
            throw new IllegalStateException("llm_judge requires Judge dependencies");
        }
        return new LlmJudgeGrader(spec, dependencies);
    }
}
