package com.paicli.eval;

/** grader type → 实现。未识别的 type 直接抛错，避免 case 拼错时静默跳过。 */
public final class GraderFactory {

    private GraderFactory() {
    }

    public static Grader create(GraderSpec spec) {
        return switch (spec.type()) {
            case "command" -> new CommandGrader(spec);
            case "output_assert" -> new OutputAssertGrader(spec);
            case "file_assert" -> new FileAssertGrader(spec);
            case "tool_selection" -> new ToolSelectionGrader(spec);
            default -> throw new IllegalArgumentException(
                    "未知 grader type: " + spec.type()
                            + "（支持 command / output_assert / file_assert / tool_selection）");
        };
    }
}
