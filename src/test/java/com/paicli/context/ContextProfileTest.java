package com.paicli.context;

import com.paicli.llm.DeepSeekClient;
import com.paicli.llm.GLMClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ContextProfile 的派生公式。
 *
 * 设计原则：没有"长 / 短模式"分档，所有参数都是 maxContextWindow 的简单函数；
 * 全模型走同一阈值（90% 触发压缩）。
 */
class ContextProfileTest {

    @Test
    void glmDerivesParamsFrom200kWindow() {
        ContextProfile profile = ContextProfile.from(new GLMClient("test-key"));

        assertEquals(200_000, profile.maxContextWindow());
        assertEquals(160_000, profile.agentTokenBudget());                  // 200k × 0.8
        assertEquals(0.90, profile.compressionTriggerRatio(), 0.001);
        assertEquals(8_192, profile.responseReserveTokens());
        assertEquals(2_048, profile.safetyMarginTokens());
        assertEquals(189_760, profile.hardInputCeilingTokens());
        assertEquals(170_784, profile.compressionTriggerTokens());          // hard ceiling × 0.9
        assertEquals(90_000, profile.shortTermMemoryBudget());              // 200k × 0.45
        assertTrue(profile.mcpResourceIndexEnabled());                      // window ≥ 32k
        assertTrue(profile.promptCachingSupported());
    }

    @Test
    void deepSeekDerivesParamsFromMillionWindow() {
        ContextProfile profile = ContextProfile.from(new DeepSeekClient("test-key"));

        assertEquals(1_000_000, profile.maxContextWindow());
        assertEquals(800_000, profile.agentTokenBudget());                  // 1M × 0.8
        assertEquals(981_808, profile.hardInputCeilingTokens());
        assertEquals(883_627, profile.compressionTriggerTokens());          // hard ceiling × 0.9
        assertEquals(450_000, profile.shortTermMemoryBudget());             // 1M × 0.45
        assertEquals("automatic-prefix-cache", profile.promptCacheMode());
        assertTrue(profile.mcpResourceIndexEnabled());
    }

    @Test
    void compressionTriggerIsAlwaysOnRegardlessOfWindowSize() {
        // 关键：长 window 也必须可触发压缩，没有"长模式不压缩"的硬开关
        for (int window : new int[]{8_000, 32_000, 128_000, 200_000, 1_000_000}) {
            ContextProfile profile = ContextProfile.custom(window, 1_000);
            assertTrue(profile.compressionTriggerRatio() > 0,
                    "window=" + window + " 必须有正的触发率");
            assertTrue(profile.compressionTriggerTokens() > 0,
                    "window=" + window + " 必须有正的触发 token 数");
        }
    }

    @Test
    void smallWindowDisablesMcpResourceIndexInjection() {
        // window < 32k 时索引注入不值当，关闭
        ContextProfile profile = ContextProfile.custom(16_000, 4_000);
        assertFalse(profile.mcpResourceIndexEnabled());
    }

    @Test
    void customProfileRespectsExplicitShortTermBudget() {
        ContextProfile profile = ContextProfile.custom(128_000, 40);

        assertEquals(40, profile.shortTermMemoryBudget());
        // 即使是 custom 也走同一压缩阈值
        assertEquals(0.90, profile.compressionTriggerRatio(), 0.001);
    }

    @Test
    void nullClientFallsBackToReasonableDefault() {
        ContextProfile profile = ContextProfile.from(null);
        assertEquals(128_000, profile.maxContextWindow());
        assertEquals(0.90, profile.compressionTriggerRatio(), 0.001);
        assertFalse(profile.promptCachingSupported());
    }

    @Test
    void explicitReservesAreClampedAndZeroCanDisableThem() {
        ContextProfile clamped = ContextProfile.custom(8_000, 4_000, 99_999, 99_999);
        assertEquals(2_000, clamped.responseReserveTokens());
        assertEquals(800, clamped.safetyMarginTokens());
        assertEquals(5_200, clamped.hardInputCeilingTokens());
        assertEquals(4_680, clamped.compressionTriggerTokens());

        ContextProfile zero = ContextProfile.custom(8_000, 4_000, 0, 0);
        assertEquals(8_000, zero.hardInputCeilingTokens());
        assertEquals(7_200, zero.compressionTriggerTokens());
    }

    @Test
    void configuredReservesAcceptZeroAndInvalidValuesFallBack() {
        String responseKey = "paicli.context.response.reserve.tokens";
        String safetyKey = "paicli.context.safety.margin.tokens";
        String oldResponse = System.getProperty(responseKey);
        String oldSafety = System.getProperty(safetyKey);
        try {
            System.setProperty(responseKey, "-1");
            System.setProperty(safetyKey, "not-a-number");
            ContextProfile fallback = ContextProfile.from(null);
            assertEquals(8_192, fallback.responseReserveTokens());
            assertEquals(2_048, fallback.safetyMarginTokens());

            System.setProperty(responseKey, "0");
            System.setProperty(safetyKey, "0");
            ContextProfile zero = ContextProfile.from(null);
            assertEquals(0, zero.responseReserveTokens());
            assertEquals(0, zero.safetyMarginTokens());

            System.setProperty(responseKey, Integer.toString(Integer.MAX_VALUE));
            System.setProperty(safetyKey, Integer.toString(Integer.MAX_VALUE));
            ContextProfile clamped = ContextProfile.from(null);
            assertEquals(32_000, clamped.responseReserveTokens());
            assertEquals(12_800, clamped.safetyMarginTokens());
        } finally {
            restoreProperty(responseKey, oldResponse);
            restoreProperty(safetyKey, oldSafety);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
