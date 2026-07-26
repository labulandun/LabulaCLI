# PaiCLI 评测沙盒镜像（Phase E3）
#
# 构建：
#   docker build -t paicli-eval:17 eval/docker
# 使用：在 case.json 里写 "docker_image": "paicli-eval:17"
#
# 默认镜像是 eclipse-temurin:17-jdk，够跑 serve --http。
# 只有当用例的验收命令需要 Maven / git / ripgrep 时才需要这个自建镜像。
#
# 注意：容器必须能出网——Agent 要调用 LLM API，没有网络就没有 Agent。
# 本沙盒提供的是文件系统 / 进程 / 资源隔离，不是网络隔离。
FROM eclipse-temurin:17-jdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        git \
        maven \
        ripgrep \
        curl \
    && rm -rf /var/lib/apt/lists/*

# 被测 jar 由 harness 以只读方式挂载到 /opt/paicli.jar
# 工作区挂载到 /work，产物目录挂载到 /out
WORKDIR /work
