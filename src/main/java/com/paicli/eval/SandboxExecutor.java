package com.paicli.eval;

import java.util.List;
import java.util.Map;

/**
 * 决定被测 PaiCLI 在哪里执行（见 docs/evaluation-system-design.md §3.5）。
 * 只影响"进程怎么起、怎么停"，CaseRunner 的其余流程与工件读取路径不变。
 */
public interface SandboxExecutor {

    /** 沙盒名，与 case.json 的 sandbox 字段一致。 */
    String name();

    /**
     * @param command ProcessBuilder 的命令行
     * @param env     要设置到宿主进程环境的变量；docker 模式为空（改走 -e 参数）
     */
    record Launch(List<String> command, Map<String, String> env) {
    }

    Launch prepare(LaunchSpec spec);

    /**
     * 进程被 destroy 之后的额外清理。docker 模式需要显式删容器，
     * 因为杀掉 `docker run` 客户端进程不保证容器停止。
     */
    default void cleanup() {
    }
}
