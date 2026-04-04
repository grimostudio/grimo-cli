package io.github.samzhu.grimo.agent.tier;

import org.springaicommunity.agents.claude.ClaudeAgentOptions;
import org.springaicommunity.agents.codex.CodexAgentOptions;
import org.springaicommunity.agents.gemini.GeminiAgentOptions;
import org.springaicommunity.agents.model.AgentOptions;
import org.springaicommunity.agents.codexsdk.types.ApprovalPolicy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * 根據 agentId + ExecutionMode 建構對應的 per-request AgentOptions。
 *
 * 設計說明：
 * - PLAN mode（主對話）：禁止檔案修改工具，agent 只能讀程式碼、寫 docs、回答問題
 *   Claude: disallowedTools=["Edit","Write","MultiEdit"]
 *   Codex:  ApprovalPolicy.SMART + fullAuto=false
 *   Gemini: yolo=false
 * - DEV mode（開發模式）：全開，搭配 worktree 隔離
 *   所有 agent: yolo=true, 無工具限制
 *
 * @see <a href="https://code.claude.com/docs/en/cli-reference">Claude Code Permission Modes</a>
 * @see <a href="https://developers.openai.com/codex/concepts/sandboxing">Codex Sandbox Modes</a>
 */
public class TierOptionsFactory {

    private static final Logger log = LoggerFactory.getLogger(TierOptionsFactory.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    /**
     * 執行模式：決定 agent 的權限等級。
     * PLAN = 主對話，限制檔案修改
     * DEV = 開發模式，全開
     */
    public enum ExecutionMode {
        PLAN,
        DEV
    }

    /** 向後相容：預設 DEV mode（現有行為不變） */
    public AgentOptions build(String agentId, String model) {
        return build(agentId, model, ExecutionMode.DEV);
    }

    /**
     * 建構指定 agent + mode 的 AgentOptions。
     */
    public AgentOptions build(String agentId, String model, ExecutionMode mode) {
        return switch (agentId) {
            case "claude" -> buildClaude(model, mode);
            case "gemini" -> buildGemini(model, mode);
            case "codex" -> buildCodex(model, mode);
            default -> throw new IllegalArgumentException("Unknown agent: " + agentId);
        };
    }

    private ClaudeAgentOptions buildClaude(String model, ExecutionMode mode) {
        var builder = ClaudeAgentOptions.builder()
                .model(model)
                .yolo(true)
                .timeout(DEFAULT_TIMEOUT);

        if (mode == ExecutionMode.PLAN) {
            builder.disallowedTools(List.of("Edit", "Write", "MultiEdit"));
        }

        return builder.build();
    }

    private GeminiAgentOptions buildGemini(String model, ExecutionMode mode) {
        return GeminiAgentOptions.builder()
                .model(model)
                .yolo(mode == ExecutionMode.DEV)
                .timeout(DEFAULT_TIMEOUT)
                .build();
    }

    /**
     * Codex PLAN/DEV 都用 fullAuto=true — Codex CLI 不支援 disallowedTools 等精細工具限制。
     * fullAuto=false 會要求 interactive terminal 確認，SDK subprocess 模式無法提供 → 空回應。
     * Codex 的隔離由 worktree + sandbox 層處理，不靠 approval policy。
     */
    private CodexAgentOptions buildCodex(String model, ExecutionMode mode) {
        var options = CodexAgentOptions.builder()
                .model(model)
                .timeout(DEFAULT_TIMEOUT)
                .approvalPolicy(ApprovalPolicy.NEVER)
                .fullAuto(true)
                .build();

        log.info("[TIER-OPTIONS] codex: model={}, mode={}, fullAuto={}, approvalPolicy={}",
                model, mode, options.isFullAuto(), options.getApprovalPolicy());
        return options;
    }
}
