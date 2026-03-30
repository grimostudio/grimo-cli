package io.github.samzhu.grimo.agent.tier;

import org.springaicommunity.agents.claude.ClaudeAgentOptions;
import org.springaicommunity.agents.codex.CodexAgentOptions;
import org.springaicommunity.agents.gemini.GeminiAgentOptions;
import org.springaicommunity.agents.model.AgentOptions;
import org.springaicommunity.agents.codexsdk.types.ApprovalPolicy;

import java.time.Duration;

/**
 * 根據 agentId 建構對應的 per-request AgentOptions（含 tier 指定的 model）。
 *
 * 設計說明：
 * - 每個 CLI agent 需要不同的 AgentOptions 子型別（ClaudeAgentOptions / GeminiAgentOptions / CodexAgentOptions）
 * - 集中管理各 agent 的共用設定（yolo=true, timeout, fullAuto 等）
 * - 在 AgentClient.run(goalText, agentOptions) 傳入，覆寫 AgentModel 的 defaultOptions
 *
 * @see <a href="https://spring-ai-community.github.io/agent-client/">AgentClient API</a>
 */
public class TierOptionsFactory {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    /**
     * 建構指定 agent 的 AgentOptions，使用 tier 選定的 model。
     *
     * @param agentId agent ID（"claude", "gemini", "codex"）
     * @param model   tier 選定的 model 名稱
     * @return 對應的 AgentOptions 子型別
     * @throws IllegalArgumentException 如果 agentId 未知
     */
    public AgentOptions build(String agentId, String model) {
        return switch (agentId) {
            case "claude" -> ClaudeAgentOptions.builder()
                    .model(model)
                    .yolo(true)
                    .timeout(DEFAULT_TIMEOUT)
                    .build();
            case "gemini" -> GeminiAgentOptions.builder()
                    .model(model)
                    .yolo(true)
                    .timeout(DEFAULT_TIMEOUT)
                    .build();
            case "codex" -> CodexAgentOptions.builder()
                    .model(model)
                    .fullAuto(true)
                    .approvalPolicy(ApprovalPolicy.NEVER)
                    .timeout(DEFAULT_TIMEOUT)
                    .build();
            default -> throw new IllegalArgumentException("Unknown agent: " + agentId);
        };
    }
}
