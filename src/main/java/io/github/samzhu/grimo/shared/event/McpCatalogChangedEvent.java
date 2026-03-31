package io.github.samzhu.grimo.shared.event;

import java.util.List;

/**
 * MCP server 新增或移除時發布。
 * 由 McpCommands 在 catalog rebuild 後 publish。
 * TUI 層 listen 後自動更新 mcp count。
 */
public record McpCatalogChangedEvent(List<String> serverNames) {}
