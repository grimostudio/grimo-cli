package io.github.samzhu.grimo.mcp;

import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.springaicommunity.agents.model.mcp.McpServerCatalog;
import org.springaicommunity.agents.model.mcp.McpServerDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 從 config.yaml 的 mcp 區段建構 McpServerCatalog。
 *
 * 設計說明：
 * - 將 Grimo config 格式轉成 AgentClient 的 Portable MCP 定義
 * - 產出的 McpServerCatalog 是 immutable 且 thread-safe，可共享給所有 AgentClient
 * - 各 CLI agent 會自動將定義轉成原生格式（Claude: --mcp-config, Gemini: settings.json）
 *
 * 支援的 type 值：
 * - stdio: 執行本地 process，需要 command 欄位，args / env 為可選
 * - sse:   透過 Server-Sent Events 連線，需要 url 欄位，headers 為可選
 * - http:  透過 HTTP 連線，需要 url 欄位，headers 為可選
 *
 * 參考：
 * - McpServerCatalog.Builder.add(String, McpServerDefinition) API (javap)
 * - McpServerDefinition.StdioDefinition(command, args, env) record
 * - McpServerDefinition.SseDefinition(url, headers) record
 * - McpServerDefinition.HttpDefinition(url, headers) record
 */
@Component
public class McpCatalogBuilder {

    private final GrimoConfig config;

    public McpCatalogBuilder(GrimoConfig config) {
        this.config = config;
    }

    /**
     * 讀取 config.yaml mcp 區段並建構 McpServerCatalog。
     *
     * config.yaml 格式範例：
     * <pre>
     * mcp:
     *   brave-search:
     *     type: stdio
     *     command: npx
     *     args:
     *       - -y
     *       - "@modelcontextprotocol/server-brave-search"
     *     env:
     *       BRAVE_API_KEY: "your-key"
     *   weather:
     *     type: sse
     *     url: http://localhost:8080/sse
     *   files:
     *     type: http
     *     url: http://localhost:9090/mcp
     * </pre>
     */
    @SuppressWarnings("unchecked")
    public McpServerCatalog build() {
        Map<String, Map<String, Object>> mcpServers = config.getMcpServers();
        if (mcpServers == null || mcpServers.isEmpty()) {
            return McpServerCatalog.of(Map.of());
        }

        var builder = McpServerCatalog.builder();
        for (var entry : mcpServers.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> serverConfig = entry.getValue();
            String type = (String) serverConfig.getOrDefault("type", "stdio");

            McpServerDefinition definition = switch (type) {
                case "stdio" -> buildStdioDefinition(serverConfig);
                case "sse" -> buildSseDefinition(serverConfig);
                case "http" -> buildHttpDefinition(serverConfig);
                default -> throw new IllegalArgumentException(
                        "Unknown MCP server type '%s' for server '%s'. Supported: stdio, sse, http"
                                .formatted(type, name));
            };

            builder.add(name, definition);
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private McpServerDefinition.StdioDefinition buildStdioDefinition(Map<String, Object> cfg) {
        String command = (String) cfg.get("command");
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("stdio MCP server requires 'command' field");
        }
        List<String> args = (List<String>) cfg.getOrDefault("args", List.of());
        Map<String, String> env = (Map<String, String>) cfg.getOrDefault("env", Map.of());
        return new McpServerDefinition.StdioDefinition(command, args, env);
    }

    @SuppressWarnings("unchecked")
    private McpServerDefinition.SseDefinition buildSseDefinition(Map<String, Object> cfg) {
        String url = (String) cfg.get("url");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("sse MCP server requires 'url' field");
        }
        Map<String, String> headers = (Map<String, String>) cfg.getOrDefault("headers", Map.of());
        return new McpServerDefinition.SseDefinition(url, headers);
    }

    @SuppressWarnings("unchecked")
    private McpServerDefinition.HttpDefinition buildHttpDefinition(Map<String, Object> cfg) {
        String url = (String) cfg.get("url");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("http MCP server requires 'url' field");
        }
        Map<String, String> headers = (Map<String, String>) cfg.getOrDefault("headers", Map.of());
        return new McpServerDefinition.HttpDefinition(url, headers);
    }
}
