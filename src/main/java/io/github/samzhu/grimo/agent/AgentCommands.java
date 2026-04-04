package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.config.GrimoConfig;
import io.github.samzhu.grimo.config.GrimoProperties;
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
 * - 推薦預設模型從 GrimoProperties（application.yaml grimo.defaults）讀取，不再硬編碼
 * - 模型 alias 解析由各 CLI 自行處理（passthrough），Grimo 不再維護 alias 表
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
    private final GrimoProperties grimoProperties;

    public AgentCommands(AgentModelRegistry registry, GrimoConfig config,
                         ApplicationEventPublisher eventPublisher,
                         GrimoProperties grimoProperties) {
        this.registry = registry;
        this.config = config;
        this.eventPublisher = eventPublisher;
        this.grimoProperties = grimoProperties;
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
            if (model == null) model = grimoProperties.getDefaults().getOrDefault(id, "");
            sb.append(String.format("%s%-10s %-14s %s%n", indicator, id, status, model));
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 統一切換 agent + model。
     *
     * 用法：
     *   /agent-use claude          → claude + 記憶模型 or 推薦預設（from GrimoProperties）
     *   /agent-use claude opus     → claude + "opus"（passthrough，由 CLI 自行解析 alias）
     *   /agent-use gemini flash    → gemini + "flash"（passthrough）
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

        // 解析 model：有 hint 就 passthrough（CLI 自行解析 alias），否則讀記憶或 properties 預設
        String model;
        if (modelHint != null && !modelHint.isBlank()) {
            model = modelHint;  // passthrough — CLI 自行解析 alias
            config.setAgentOption(agentId, "model", model);
        } else {
            // 讀記憶，沒有就用 GrimoProperties 推薦預設
            model = config.getAgentOption(agentId, "model");
            if (model == null) {
                model = grimoProperties.getDefaults().getOrDefault(agentId, "unknown");
            }
        }

        config.setDefaultAgent(agentId);
        config.setDefaultModel(model);
        eventPublisher.publishEvent(new AgentSwitchedEvent(agentId, model));

        return "Switched to " + agentId + " \u00b7 " + model;
    }
}
