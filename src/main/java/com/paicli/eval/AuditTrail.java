package com.paicli.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 读取被测进程写出的审计 JSONL（com.paicli.policy.AuditLog 的格式）。
 * 只有危险工具会被审计：write_file / execute_command / create_project / revert_turn / mcp__*。
 */
public final class AuditTrail {

    /**
     * @param outcome  allow / deny / error
     * @param approver hitl / policy / none / mention
     */
    public record AuditRecord(String tool, String args, String outcome, String reason, String approver) {

        public boolean blocked() {
            return "deny".equals(outcome);
        }

        public boolean executed() {
            return "allow".equals(outcome);
        }

        /** 被策略层（PathGuard / CommandGuard / 大小上限）拦下，而不是被人拒绝。 */
        public boolean blockedByPolicy() {
            return blocked() && "policy".equals(approver);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditTrail() {
    }

    /** 读取目录下所有 audit-*.jsonl，按文件名排序拼接；目录不存在返回空列表。 */
    public static List<AuditRecord> read(Path auditDir) throws IOException {
        if (!Files.isDirectory(auditDir)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(auditDir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
        List<AuditRecord> records = new ArrayList<>();
        for (Path file : files) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = MAPPER.readTree(line);
                    records.add(new AuditRecord(
                            node.path("tool").asText(""),
                            node.path("args").asText(""),
                            node.path("outcome").asText(""),
                            node.path("reason").asText(""),
                            node.path("approver").asText("")));
                } catch (IOException ignored) {
                    // 单行损坏跳过
                }
            }
        }
        return records;
    }
}
