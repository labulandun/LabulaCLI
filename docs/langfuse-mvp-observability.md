# Langfuse 最小评测闭环

## 已交付埋点

当前只覆盖 ReAct 主路径，形成如下 Trace 树：

```text
paicli.react.run (agent/root)
├── paicli.llm.chat (generation)
├── read_file / execute_command / ... (tool)
└── paicli.llm.chat (generation)
```

- 根 observation：记录本轮输入、最终回答、provider、model、session 与 `react` tag。
- generation：记录消息输入、模型输出、model、provider 和 token usage；`input` 不含 cached tokens，另记 `input_cached_tokens`，`total` 保持供应商返回的总量口径。
- tool：记录 tool name、tool call id、参数、结果与异常；并行工具会显式继承根上下文。
- Runtime API：`turn.completed` / `turn.failed` 事件会返回 `trace_id` 与 `root_observation_id`，供 Dataset Run 和 Score 关联。
- 数据保护：不导出 `reasoning_content` 与图片 base64；常见 Authorization/API Key 会脱敏；单个属性最大 16 KiB。

当前不覆盖 `/plan`、`/team` 与独立 SubAgent 根节点；应在本 MVP 验收通过后扩展。

## 配置

把 `.env.example` 中 OpenTelemetry 配置复制到 `.env`，填入自己的 Langfuse 区域 endpoint 和 Basic Auth。直接通过 OTLP 接入 Langfuse v4 时必须保留 `x-langfuse-ingestion-version=4`。

```dotenv
PAICLI_OTEL_ENABLED=true
OTEL_SERVICE_NAME=paicli
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=none
OTEL_LOGS_EXPORTER=none
OTEL_EXPORTER_OTLP_TRACES_PROTOCOL=http/protobuf
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=https://cloud.langfuse.com/api/public/otel/v1/traces
OTEL_EXPORTER_OTLP_TRACES_HEADERS=Authorization=Basic BASE64_AUTH,x-langfuse-ingestion-version=4
```

启动一次普通 ReAct 任务后，在 Langfuse 中确认只有一个根 observation，generation/tool 都是它的子节点，generation 的 model 和 usage 可见。

## 10 条 Dataset 与三个 Score

1. 在 Langfuse 创建 `paicli-mvp-v1`，先固定 10 条 item；每条至少包含 `input.prompt`、`expectedOutput`、`metadata.case_id` 和验收命令。
2. 每条 item 只执行一次。通过 Runtime API 运行时，从 `turn.completed` 读取 `trace_id` 和 `root_observation_id`；CLI 手工运行时可在 Langfuse Trace 页面取对应 ID。
3. 评测器负责执行验收命令并写入以下 Score，应用本身不把“进程正常结束”误判成任务成功：

| Score | 类型 | 口径 |
|---|---|---|
| `tests_pass` | BOOLEAN | 指定测试全部通过为 1，否则为 0 |
| `task_success` | BOOLEAN | expected output / 验收标准全部满足为 1，否则为 0 |
| `token_count` | NUMERIC | 本条 Trace 下所有 generation 的 `usage_details.total` 之和 |

Score 通过 Langfuse `POST /api/public/scores` 写入；同时传 `traceId` 和根 `observationId`，并使用稳定 `id` 防止重复写入：

```json
{
  "id": "<case-id>-task_success",
  "traceId": "<trace_id>",
  "observationId": "<root_observation_id>",
  "name": "task_success",
  "value": 1,
  "dataType": "BOOLEAN",
  "comment": "acceptance checks passed"
}
```

## MVP 验收门槛

- Dataset item 数严格等于 10，每条恰好产生一个 ReAct 根 Trace。
- 每条 Trace 至少有一个 generation；触发工具的用例能看到 tool 子节点，且无孤儿 span。
- 10 条 Trace 都有 `tests_pass`、`task_success`、`token_count`，共 30 个 Score。
- 任取一条核对：根输入/输出正确、reasoning 与密钥未出现、token 三段口径正确。

