package io.github.samzhu.grimo.mcp.client;

/**
 * 記錄 MCP 伺服器連線的描述資訊。
 * 用於在 registry 中儲存及查詢已連線的 MCP 伺服器狀態，
 * 包含傳輸方式（stdio 或 sse）、啟動指令、以及該伺服器所提供的工具數量。
 *
 * @param name      連線識別名稱
 * @param transport 傳輸類型，"stdio" 或 "sse"
 * @param command   stdio 啟動指令或 SSE URL
 * @param toolCount 該伺服器提供的工具數量
 */
public record McpConnectionInfo(
    String name,
    String transport,   // "stdio" or "sse"
    String command,     // stdio command or SSE URL
    int toolCount
) {}
