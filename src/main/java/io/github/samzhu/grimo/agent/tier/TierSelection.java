package io.github.samzhu.grimo.agent.tier;

/**
 * TierRouter 的路由結果。
 *
 * @param agentId 選定的 agent ID（如 "claude", "gemini", "codex"）
 * @param model   選定的 model 名稱（如 "claude-haiku-4", "gemini-2.5-flash"）
 * @param tier    解析後的 Tier 等級
 * @param source  Tier 的來源（除錯/日誌用）
 */
public record TierSelection(
    String agentId,
    String model,
    Tier tier,
    String source
) {}
