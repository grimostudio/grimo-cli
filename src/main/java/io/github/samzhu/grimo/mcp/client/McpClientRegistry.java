package io.github.samzhu.grimo.mcp.client;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 連線資訊的線程安全註冊表。
 * 使用 ConcurrentHashMap 儲存已建立的 MCP 伺服器連線描述，
 * 以支援運行時動態新增及移除 MCP 伺服器，無需重新啟動應用程式。
 *
 * 設計考量：不使用 Spring DI 管理，而是作為普通 Java 物件，
 * 使得 MCP 連線可在 CLI 指令中動態管理（如 mcp add / mcp remove）。
 */
public class McpClientRegistry {

    private final ConcurrentHashMap<String, McpConnectionInfo> connections = new ConcurrentHashMap<>();

    public void register(String name, McpConnectionInfo info) {
        connections.put(name, info);
    }

    public void remove(String name) {
        connections.remove(name);
    }

    public Optional<McpConnectionInfo> get(String name) {
        return Optional.ofNullable(connections.get(name));
    }

    public List<McpConnectionInfo> listAll() {
        return List.copyOf(connections.values());
    }
}
