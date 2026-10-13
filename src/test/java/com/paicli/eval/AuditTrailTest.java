package com.paicli.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditTrailTest {

    @TempDir
    Path tempDir;

    @Test
    void readsRecordsAcrossDailyFilesInOrder() throws Exception {
        Path auditDir = tempDir.resolve("audit");
        Files.createDirectories(auditDir);
        Files.writeString(auditDir.resolve("audit-2026-07-25.jsonl"),
                "{\"tool\":\"write_file\",\"args\":\"{}\",\"outcome\":\"allow\",\"approver\":\"none\"}\n",
                StandardCharsets.UTF_8);
        Files.writeString(auditDir.resolve("audit-2026-07-26.jsonl"),
                "{\"tool\":\"execute_command\",\"args\":\"rm -rf /\",\"outcome\":\"deny\","
                        + "\"reason\":\"禁止 rm -rf\",\"approver\":\"policy\"}\n"
                        + "{ broken json\n",
                StandardCharsets.UTF_8);

        List<AuditTrail.AuditRecord> records = AuditTrail.read(auditDir);
        assertEquals(2, records.size(), "损坏行应被跳过");
        assertEquals("write_file", records.get(0).tool());
        assertTrue(records.get(0).executed());
        assertFalse(records.get(0).blocked());

        AuditTrail.AuditRecord denied = records.get(1);
        assertTrue(denied.blocked());
        assertTrue(denied.blockedByPolicy());
        assertFalse(denied.executed());
    }

    @Test
    void missingDirectoryYieldsEmptyList() throws Exception {
        assertTrue(AuditTrail.read(tempDir.resolve("nope")).isEmpty());
    }

    @Test
    void hitlDenialIsNotCountedAsPolicyBlock() {
        AuditTrail.AuditRecord record =
                new AuditTrail.AuditRecord("execute_command", "rm x", "deny", "用户拒绝", "hitl");
        assertTrue(record.blocked());
        assertFalse(record.blockedByPolicy());
    }
}
