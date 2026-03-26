package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

/**
 * Spring Shell CLI commands for managing agent models.
 *
 * 設計說明：
 * - 使用 AgentModelRegistry（取代 AgentProviderRegistry）
 * - 全部都是 CLI agent，移除 TYPE 欄位
 * - /agent-model 切換 model 名稱（持久化到 config.yaml）
 */
@Component
public class AgentCommands {

    private final AgentModelRegistry registry;
    private final GrimoConfig config;

    public AgentCommands(AgentModelRegistry registry, GrimoConfig config) {
        this.registry = registry;
        this.config = config;
    }

    @Command(name = "agent-list", description = "List all configured agents")
    public String list() {
        var models = registry.listAll();
        if (models.isEmpty()) {
            return "No agents available. Install a CLI agent (claude, gemini, or codex).";
        }

        var sb = new StringBuilder();
        sb.append(String.format("  %-15s %-15s%n", "ID", "STATUS"));
        for (var entry : models.entrySet()) {
            String status = entry.getValue().isAvailable() ? "ready" : "not available";
            sb.append(String.format("  %-15s %-15s%n", entry.getKey(), status));
        }
        return sb.toString();
    }

    @Command(name = "agent-use", description = "Switch default agent")
    public String use(String agentId) {
        if (registry.get(agentId) == null) {
            return "Agent not found: " + agentId + ". Run '/agent-list' to see available agents.";
        }
        config.setDefaultAgent(agentId);
        return "Default agent switched to: " + agentId;
    }

    @Command(name = "agent-model", description = "Switch default model")
    public String model(String modelName) {
        config.setDefaultModel(modelName);
        return "Default model switched to: " + modelName;
    }
}
