package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.provider.AgentProvider;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

/**
 * Spring Shell CLI commands for managing agent providers.
 * Provides 'agent list' command to display all registered providers
 * with their type (API/CLI) and availability status.
 *
 * Uses Spring Shell 4.0 @Command annotation model (replaces legacy @ShellComponent/@ShellMethod).
 * Reference: https://github.com/spring-projects/spring-shell/wiki/v4-migration-guide
 */
@Component
public class AgentCommands {

    private final AgentProviderRegistry registry;
    private final GrimoConfig config;

    public AgentCommands(AgentProviderRegistry registry, GrimoConfig config) {
        this.registry = registry;
        this.config = config;
    }

    /**
     * 列出所有已註冊的 agent provider 及其狀態。
     * kebab-case 扁平命令：/agent-list
     */
    @Command(name = "agent-list", description = "List all configured agent providers")
    public String list() {
        var providers = registry.listAll();
        if (providers.isEmpty()) {
            return "No agents configured. Run 'agent add <provider>' to add one.";
        }

        var sb = new StringBuilder();
        sb.append(String.format("  %-15s %-6s %-8s%n", "ID", "TYPE", "STATUS"));
        for (AgentProvider p : providers) {
            String status = p.isAvailable() ? "ready" : "not available";
            sb.append(String.format("  %-15s %-6s %-8s%n", p.id(), p.type(), status));
        }
        return sb.toString();
    }

    /**
     * 切換預設 agent provider，設定持久化至 workspace config.yaml。
     * Switch default agent provider and persist to config.
     */
    @Command(name = "agent-use", description = "Switch default agent provider")
    public String use(String agentId) {
        if (registry.get(agentId).isEmpty()) {
            return "Agent not found: " + agentId + ". Run 'agent' to see available agents.";
        }
        config.setDefaultAgent(agentId);
        return "Default agent switched to: " + agentId;
    }

    /**
     * 切換預設模型名稱，設定持久化至 workspace config.yaml。
     * Switch default model and persist to config.
     */
    @Command(name = "agent-model", description = "Switch default model")
    public String model(String modelName) {
        config.setDefaultModel(modelName);
        return "Default model switched to: " + modelName;
    }
}
