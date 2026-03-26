package io.github.samzhu.grimo.mcp;

import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Spring Shell CLI commands for displaying MCP server configuration.
 * Reads MCP server definitions from GrimoConfig (config.yaml mcp 區段).
 *
 * 設計說明：
 * - 取代舊版使用 McpClientRegistry 的實作，改為直接讀取 GrimoConfig.getMcpServers()
 * - MCP server 連線由各 AgentModel（Claude/Gemini/Codex）透過 McpServerCatalog 管理
 * - 此命令只提供 read-only 的設定查看，實際連線生命週期由 AgentClient 負責
 *
 * Uses Spring Shell 4.0 @Command annotation model (replaces legacy @ShellComponent/@ShellMethod).
 * Reference: https://github.com/spring-projects/spring-shell/wiki/v4-migration-guide
 */
@Component
public class McpCommands {

    private final GrimoConfig config;

    public McpCommands(GrimoConfig config) {
        this.config = config;
    }

    /**
     * 列出所有 config.yaml 中設定的 MCP server。
     * kebab-case 扁平命令：/mcp-list
     */
    @Command(name = "mcp-list", description = "List MCP server configurations")
    public String list() {
        Map<String, Map<String, Object>> servers = config.getMcpServers();
        if (servers.isEmpty()) {
            return "No MCP servers configured.";
        }

        var sb = new StringBuilder();
        sb.append(String.format("  %-20s %-10s %-30s%n", "NAME", "TYPE", "COMMAND/URL"));
        for (var entry : servers.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> cfg = entry.getValue();
            String type = (String) cfg.getOrDefault("type", "stdio");
            String commandOrUrl = type.equals("stdio")
                    ? (String) cfg.getOrDefault("command", "")
                    : (String) cfg.getOrDefault("url", "");
            sb.append(String.format("  %-20s %-10s %-30s%n", name, type, commandOrUrl));
        }
        return sb.toString();
    }
}
