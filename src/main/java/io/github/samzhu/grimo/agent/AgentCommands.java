package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.config.GrimoConfig;
import io.github.samzhu.grimo.shared.event.AgentSwitchedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Agent 管理指令：統一的 /agent-use 處理 agent + model 切換。
 *
 * 設計說明：
 * - 合併原本的 /agent-use 和 /agent-model 為單一指令
 * - 懶人原則：只指定 agent → 自動帶推薦模型或記憶的模型
 * - Per-agent 模型記憶：切到 opus 後換 gemini 再換回 claude，記住 opus
 * - 智慧匹配：簡寫 "opus" → "claude-opus-4-6"
 *
 * @see <a href="https://code.claude.com/docs/en/model-config">Claude Code Model Config</a>
 * @see <a href="https://github.com/google-gemini/gemini-cli">Gemini CLI</a>
 * @see <a href="https://github.com/openai/codex">Codex CLI</a>
 */
@Component
public class AgentCommands {

    private final AgentModelRegistry registry;
    private final GrimoConfig config;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 各 CLI agent 的推薦預設模型（對齊各 CLI 官方預設）。
     */
    public static final Map<String, String> RECOMMENDED_MODELS = Map.of(
            "claude", "claude-sonnet-4-6",
            "gemini", "gemini-2.5-pro",
            "codex", "o4-mini"
    );

    /**
     * 簡寫 → 完整模型 ID 對應表。
     * key 格式：agentId + ":" + alias
     */
    static final Map<String, String> MODEL_ALIASES = Map.ofEntries(
            Map.entry("claude:opus", "claude-opus-4-6"),
            Map.entry("claude:sonnet", "claude-sonnet-4-6"),
            Map.entry("claude:haiku", "claude-haiku-4-5"),
            Map.entry("gemini:pro", "gemini-2.5-pro"),
            Map.entry("gemini:flash", "gemini-2.5-flash"),
            Map.entry("codex:o4-mini", "o4-mini"),
            Map.entry("codex:o3", "o3")
    );

    public AgentCommands(AgentModelRegistry registry, GrimoConfig config,
                         ApplicationEventPublisher eventPublisher) {
        this.registry = registry;
        this.config = config;
        this.eventPublisher = eventPublisher;
    }

    @Command(name = "agent-list", description = "List all configured agents")
    public String list() {
        var models = registry.listAll();
        if (models.isEmpty()) {
            return "No agents available. Install a CLI agent (claude, gemini, or codex).";
        }

        String defaultAgent = config.getDefaultAgent();
        if (defaultAgent == null) {
            defaultAgent = models.entrySet().stream()
                    .filter(e -> e.getValue().isAvailable())
                    .map(Map.Entry::getKey)
                    .findFirst().orElse("");
        }

        var sb = new StringBuilder();
        for (var entry : models.entrySet()) {
            String id = entry.getKey();
            String indicator = id.equals(defaultAgent) ? "> " : "  ";
            String status = entry.getValue().isAvailable() ? "ready" : "not available";
            String model = config.getAgentOption(id, "model");
            if (model == null) model = RECOMMENDED_MODELS.getOrDefault(id, "");
            sb.append(String.format("%s%-10s %-14s %s%n", indicator, id, status, model));
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 統一切換 agent + model。
     *
     * 用法：
     *   /agent-use claude          → claude + 記憶模型 or 推薦預設
     *   /agent-use claude opus     → claude + claude-opus-4-6（智慧匹配 + 存記憶）
     *   /agent-use gemini flash    → gemini + gemini-2.5-flash
     *
     * @param rawArgs 原始參數字串，格式：<agentId> [modelHint]
     */
    @Command(name = "agent-use", description = "Switch agent (auto-picks model)")
    public String use(String rawArgs) {
        if (rawArgs == null || rawArgs.isBlank()) {
            return "Usage: /agent-use <agent> [model]\nExample: /agent-use claude opus";
        }
        String[] parts = rawArgs.trim().split("\\s+", 2);
        String agentId = parts[0];
        String modelHint = parts.length > 1 ? parts[1] : null;

        // 驗證 agent 存在
        if (registry.get(agentId) == null) {
            return "Agent not found: " + agentId + ". Run '/agent-list' to see available agents.";
        }

        // 解析 model
        String model;
        if (modelHint != null && !modelHint.isBlank()) {
            model = resolveModel(agentId, modelHint);
            config.setAgentOption(agentId, "model", model);
        } else {
            // 讀記憶，沒有就用推薦預設
            model = config.getAgentOption(agentId, "model");
            if (model == null) {
                model = RECOMMENDED_MODELS.getOrDefault(agentId, "unknown");
            }
        }

        config.setDefaultAgent(agentId);
        config.setDefaultModel(model);
        eventPublisher.publishEvent(new AgentSwitchedEvent(agentId, model));

        return "Switched to " + agentId + " \u00b7 " + model;
    }

    /**
     * 智慧匹配：簡寫 alias → 完整模型 ID。
     * 先查 alias 表，不匹配則直接當完整 model ID。
     */
    private String resolveModel(String agentId, String hint) {
        String aliasKey = agentId + ":" + hint.toLowerCase();
        return MODEL_ALIASES.getOrDefault(aliasKey, hint);
    }
}
