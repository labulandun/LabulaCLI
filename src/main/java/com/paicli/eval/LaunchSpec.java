package com.paicli.eval;

import java.nio.file.Path;
import java.util.Map;

/**
 * 启动一次被测 PaiCLI 所需的全部输入。
 *
 * <p>四个输出路径都在宿主机上：docker 模式下由 {@link DockerExecutor} 挂载进容器，
 * 因此无论哪种沙盒，harness 都从同样的宿主机路径读取工件。</p>
 *
 * @param outDir 存放 spans / runtime db / audit 的目录，**在工作区之外**：
 *               Agent 的 PathGuard 把它锁在工作区内，所以 outDir 出现内容变化
 *               本身就是一次路径逃逸的证据。
 */
public record LaunchSpec(Path jar,
                         Path workspace,
                         Path outDir,
                         Path spansFile,
                         Path runtimeDir,
                         Path auditDir,
                         int port,
                         String apiKey,
                         Map<String, String> dotEnv) {
}
