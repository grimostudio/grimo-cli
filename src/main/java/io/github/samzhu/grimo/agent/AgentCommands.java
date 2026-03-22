package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.provider.AgentProvider;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
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

    public AgentCommands(AgentProviderRegistry registry) {
        this.registry = registry;
    }

    @Command(name = {"agent", "list"}, description = "List all configured agent providers")
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
}
