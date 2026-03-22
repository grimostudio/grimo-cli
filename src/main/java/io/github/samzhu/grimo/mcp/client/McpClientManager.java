package io.github.samzhu.grimo.mcp.client;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理 MCP 客戶端的生命週期，包括建立連線、初始化及關閉。
 * 使用 MCP Java SDK（io.modelcontextprotocol.sdk:mcp-core 1.1.0）直接操作，
 * 而非 Spring AI MCP Starter，以實現運行時動態新增/移除 MCP 伺服器。
 *
 * 設計考量：
 * - 透過 ConcurrentHashMap 管理活躍的 McpSyncClient 實例
 * - 連線資訊同步登記到 McpClientRegistry 供查詢
 * - 支援 stdio 傳輸模式（透過子進程啟動 MCP 伺服器）
 *
 * 參考：https://java.sdk.modelcontextprotocol.io/latest-snapshot/client/
 */
public class McpClientManager {

    private final McpClientRegistry registry;
    private final ConcurrentHashMap<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    public McpClientManager(McpClientRegistry registry) {
        this.registry = registry;
    }

    /**
     * 透過 stdio 傳輸方式新增 MCP 伺服器連線。
     * 解析指令字串為執行檔與參數，啟動子進程並初始化 MCP 協議交握，
     * 最後查詢該伺服器提供的工具數量並登記至 registry。
     *
     * @param name    連線識別名稱
     * @param command 完整的 stdio 啟動指令（如 "npx @modelcontextprotocol/server-github"）
     * @return 已登記的連線資訊，包含工具數量
     */
    public McpConnectionInfo addStdio(String name, String command) {
        String[] parts = command.split("\\s+");
        String executable = parts[0];
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);

        var params = ServerParameters.builder(executable)
            .args(args)
            .build();
        var transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());
        var client = McpClient.sync(transport).build();
        client.initialize();

        clients.put(name, client);

        var tools = client.listTools();
        int toolCount = tools.tools().size();
        var info = new McpConnectionInfo(name, "stdio", command, toolCount);
        registry.register(name, info);

        return info;
    }

    /**
     * 移除指定名稱的 MCP 伺服器連線，關閉客戶端並從 registry 中移除。
     *
     * @param name 連線識別名稱
     */
    public void remove(String name) {
        var client = clients.remove(name);
        if (client != null) {
            client.close();
        }
        registry.remove(name);
    }

    /**
     * 關閉所有活躍的 MCP 客戶端連線。
     * 用於應用程式關閉時的清理作業。
     */
    public void closeAll() {
        clients.forEach((name, client) -> {
            try { client.close(); } catch (Exception _) {}
        });
        clients.clear();
    }
}
