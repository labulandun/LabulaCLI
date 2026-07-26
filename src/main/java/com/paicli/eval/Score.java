package com.paicli.eval;

/**
 * 单项评分。BOOLEAN 用 0/1 表示，口径与 Langfuse Score 对齐，
 * 方便后续阶段直接上传（见 docs/evaluation-system-design.md §3.2）。
 */
public record Score(String name, double value, DataType dataType, String comment) {

    public enum DataType { BOOLEAN, NUMERIC, CATEGORICAL }

    public static Score bool(String name, boolean pass, String comment) {
        return new Score(name, pass ? 1 : 0, DataType.BOOLEAN, comment);
    }

    /** BOOLEAN 分是否通过；NUMERIC/CATEGORICAL 不参与 task_success 判定。 */
    public boolean passed() {
        return dataType == DataType.BOOLEAN && value >= 1.0;
    }
}
