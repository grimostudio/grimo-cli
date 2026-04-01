package io.github.samzhu.grimo.mcp;

import io.github.samzhu.grimo.config.GrimoConfig;
import io.github.samzhu.grimo.shared.event.McpCatalogChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP server 管理指令：/mcp、/mcp-add、/mcp-remove。
 *
 * 設計說明：
 * - 平坦命名（每個指令獨立出現在斜線選單，使用者一眼看到所有可用操作）
 * - name 和 url 使用位置參數（對齊 Claude Code / Codex CLI 業界慣例）
 * - --transport 可省略：有 url → sse，有 --exec → stdio（自動推斷）
 * - --exec 值以空格切割：第一個 token 為 command，其餘為 args
 * - McpCatalogBuilder.rebuild() 更新 volatile 快取，下一次 agent 呼叫自動帶上新 MCP
 *
 * 參考：
 * - Claude Code: https://code.claude.com/docs/en/mcp
 * - Codex CLI: https://developers.openai.com/codex/mcp
 * - Spring Shell 4.0: https://docs.spring.io/spring-shell/reference/commands/syntax.html
 */
@Component
public class McpCommands {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]+");
    private static final Set<String> VALID_TRANSPORTS = Set.of("stdio", "sse", "http");

    private final GrimoConfig config;
    private final McpCatalogBuilder catalogBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public McpCommands(GrimoConfig config, McpCatalogBuilder catalogBuilder, ApplicationEventPublisher eventPublisher) {
        this.config = config;
        this.catalogBuilder = catalogBuilder;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 列出所有 config.yaml 中設定的 MCP server。
     */
    @Command(name = "mcp", description = "Manage MCP servers")
    public String list() {
        Map<String, Map<String, Object>> servers = config.getMcpServers();
        if (servers.isEmpty()) {
            return "No MCP servers configured.";
        }

        var sb = new StringBuilder();
        sb.append("Manage MCP servers\n");
        sb.append(servers.size()).append(servers.size() == 1 ? " server" : " servers").append("\n\n");
        sb.append(String.format("  %-20s %-10s %-30s%n", "NAME", "TYPE", "COMMAND/URL"));
        for (var entry : servers.entrySet()) {
            String serverName = entry.getKey();
            Map<String, Object> cfg = entry.getValue();
            String type = (String) cfg.getOrDefault("type", "stdio");
            String commandOrUrl = type.equals("stdio")
                    ? (String) cfg.getOrDefault("command", "")
                    : (String) cfg.getOrDefault("url", "");
            sb.append(String.format("  %-20s %-10s %-30s%n", serverName, type, commandOrUrl));
        }
        return sb.toString();
    }

    /**
     * 新增 MCP server 到 config.yaml 並即時重建 catalog。
     *
     * 設計說明：
     * - name（位置 0）和 url（位置 1）使用 @Argument，對齊業界 CLI 慣例
     * - transport 可省略，依據 url/exec 自動推斷（有 url → sse，有 exec → stdio）
     * - --exec 值以空格切割：第一個 token 為 command，其餘為 args
     * - 限制：--exec 中路徑含空格時無法正確切割（業界 CLI 共通限制）
     */
    @Command(name = "mcp-add", description = "Add an MCP server")
    public String add(
            @Argument(index = 0, description = "Server name",
                      defaultValue = "") String name,
            @Argument(index = 1, description = "URL (for sse/http)",
                      defaultValue = "") String url,
            @Option(longName = "transport", shortName = 't',
                    description = "Transport: stdio, sse, http") String transport,
            @Option(longName = "exec", shortName = 'e',
                    description = "Stdio command (space-separated)") String exec) {

        // === 缺少 name → 顯示 usage ===
        if (name.isEmpty()) {
            return """
                    Usage: /mcp-add <name> [<url>] [--transport <stdio|sse|http>] [--exec "<command>"]

                    Examples:
                      /mcp-add deepwiki https://mcp.deepwiki.com/sse
                      /mcp-add deepwiki --transport sse https://mcp.deepwiki.com/sse
                      /mcp-add fs --exec "npx -y @modelcontextprotocol/server-filesystem /tmp"
                      /mcp-add fs --transport stdio --exec "npx -y @pkg /tmp\"""";
        }

        // === name 格式驗證 ===
        if (!NAME_PATTERN.matcher(name).matches()) {
            return "Invalid name '" + name + "'. Use only letters, digits, hyphens, underscores.";
        }

        // === transport 自動推斷 ===
        if (transport == null || transport.isEmpty()) {
            if (!url.isEmpty()) {
                transport = "sse";
            } else if (exec != null && !exec.isEmpty()) {
                transport = "stdio";
            } else {
                return """
                        Usage: /mcp-add <name> [<url>] [--transport <stdio|sse|http>] [--exec "<command>"]

                        Examples:
                          /mcp-add deepwiki https://mcp.deepwiki.com/sse
                          /mcp-add fs --exec "npx -y @modelcontextprotocol/server-filesystem /tmp\"""";
            }
        }

        // === transport 驗證 ===
        if (!VALID_TRANSPORTS.contains(transport)) {
            return "Invalid transport '" + transport + "'. Supported: stdio, sse, http";
        }

        // === type-specific 驗證 ===
        if (("sse".equals(transport) || "http".equals(transport)) && url.isEmpty()) {
            return "URL is required for transport '" + transport + "'";
        }
        if (("sse".equals(transport) || "http".equals(transport)) && !url.isEmpty()) {
            try {
                URI.create(url).toURL();
            } catch (Exception e) {
                return "Invalid URL: '" + url + "'";
            }
        }
        if ("stdio".equals(transport) && (exec == null || exec.isEmpty())) {
            return "--exec is required for transport 'stdio'";
        }

        // === 重複檢查 ===
        if (config.getMcpServers().containsKey(name)) {
            return "MCP server '" + name + "' already exists. Remove it first.";
        }

        // === 組裝 server 定義 ===
        var serverDef = new LinkedHashMap<String, Object>();
        serverDef.put("type", transport);
        if ("stdio".equals(transport)) {
            // --exec "npx -y @pkg /tmp" → command: npx, args: [-y, @pkg, /tmp]
            String[] tokens = exec.trim().split("\\s+");
            serverDef.put("command", tokens[0]);
            if (tokens.length > 1) {
                serverDef.put("args", Arrays.asList(Arrays.copyOfRange(tokens, 1, tokens.length)));
            }
        } else {
            serverDef.put("url", url);
        }

        // === 寫入 + 重建 ===
        config.setMcpServer(name, serverDef);
        catalogBuilder.rebuild();
        eventPublisher.publishEvent(new McpCatalogChangedEvent(catalogBuilder.getServerNames()));

        return "Added: " + name + " (" + transport + ")";
    }

    /**
     * 從 config.yaml 移除 MCP server 並即時重建 catalog。
     */
    @Command(name = "mcp-remove", description = "Remove an MCP server")
    public String remove(
            @Argument(index = 0, description = "Server name",
                      defaultValue = "") String name) {

        if (name.isEmpty()) {
            return "Usage: /mcp-remove <name>";
        }

        boolean removed = config.removeMcpServer(name);
        if (!removed) {
            return "MCP server '" + name + "' not found.";
        }

        catalogBuilder.rebuild();
        eventPublisher.publishEvent(new McpCatalogChangedEvent(catalogBuilder.getServerNames()));
        return "Removed: " + name;
    }
}
