package io.github.samzhu.grimo.mcp;

import io.github.samzhu.grimo.mcp.client.McpClientRegistry;
import io.github.samzhu.grimo.mcp.client.McpConnectionInfo;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

/**
 * Spring Shell CLI commands for managing MCP server connections.
 * Provides 'mcp list' to display all registered MCP connections
 * and 'mcp remove' to disconnect a specific MCP server.
 *
 * Uses Spring Shell 4.0 @Command annotation model (replaces legacy @ShellComponent/@ShellMethod).
 * Reference: https://github.com/spring-projects/spring-shell/wiki/v4-migration-guide
 */
@Component
public class McpCommands {

    private final McpClientRegistry registry;

    public McpCommands(McpClientRegistry registry) {
        this.registry = registry;
    }

    /**
     * 列出所有 MCP server 連線。
     * kebab-case 扁平命令：/mcp-list
     */
    @Command(name = "mcp-list", description = "List MCP server connections")
    public String list() {
        var connections = registry.listAll();
        if (connections.isEmpty()) {
            return "No MCP connections configured.";
        }

        var sb = new StringBuilder();
        sb.append(String.format("  %-12s %-10s %-6s%n", "NAME", "TRANSPORT", "TOOLS"));
        for (McpConnectionInfo c : connections) {
            sb.append(String.format("  %-12s %-10s %-6d%n", c.name(), c.transport(), c.toolCount()));
        }
        return sb.toString();
    }

    @Command(name = "mcp-remove", description = "Remove an MCP server connection")
    public String remove(String name) {
        if (registry.get(name).isEmpty()) {
            return "MCP connection not found: " + name;
        }
        registry.remove(name);
        return "MCP connection removed: " + name;
    }
}
