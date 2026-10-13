package com.paicli.context;

import java.io.IOException;

/** Controlled local failure raised before an over-window request reaches an LLM provider. */
public final class ContextViewBuildException extends IOException {
    public static final String CODE = "CONTEXT_VIEW_BUILD_FAILED";

    private final RequestContextBudget budget;

    public ContextViewBuildException(RequestContextBudget budget) {
        super(message(budget));
        this.budget = budget;
    }

    public RequestContextBudget budget() {
        return budget;
    }

    private static String message(RequestContextBudget budget) {
        if (budget == null) {
            return CODE + ": 无法构建安全的模型请求视图";
        }
        return CODE + ": estimated input " + budget.estimatedInputTokens()
                + " exceeds hard input ceiling " + budget.hardInputCeilingTokens()
                + " (window=" + budget.maxContextWindow()
                + ", response_reserve=" + budget.responseReserveTokens()
                + ", safety_margin=" + budget.safetyMarginTokens()
                + ", over=" + budget.overHardCeilingTokens() + ")";
    }
}
