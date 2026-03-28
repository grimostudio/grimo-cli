package io.github.samzhu.grimo.mcp;

import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class McpCommandsTest {

    GrimoConfig config;
    McpCatalogBuilder catalogBuilder;
    McpCommands commands;

    @BeforeEach
    void setUp() {
        config = mock(GrimoConfig.class);
        catalogBuilder = mock(McpCatalogBuilder.class);
        commands = new McpCommands(config, catalogBuilder);
    }

    // === /mcp（列表）===

    @Test
    void listShouldShowServerList() {
        when(config.getMcpServers()).thenReturn(Map.of(
                "deepwiki", Map.of("type", "sse", "url", "https://mcp.deepwiki.com/sse")
        ));

        String result = commands.list();

        assertThat(result).contains("Manage MCP servers");
        assertThat(result).contains("1 server");
        assertThat(result).contains("deepwiki");
        assertThat(result).contains("sse");
    }

    @Test
    void listShouldShowEmptyMessage() {
        when(config.getMcpServers()).thenReturn(Map.of());

        String result = commands.list();

        assertThat(result).contains("No MCP servers configured");
    }

    @Test
    void listShouldShowSseServer() {
        when(config.getMcpServers()).thenReturn(Map.of(
                "weather", Map.of("type", "sse", "url", "http://localhost:8080/sse")
        ));

        String result = commands.list();

        assertThat(result).contains("weather");
        assertThat(result).contains("sse");
        assertThat(result).contains("http://localhost:8080/sse");
    }

    // === /mcp-add（SSE）===

    @Test
    void addSseShouldPersistAndRebuild() {
        when(config.getMcpServers()).thenReturn(Map.of());

        String result = commands.add("deepwiki", "https://mcp.deepwiki.com/sse", "sse", null);

        assertThat(result).contains("Added").contains("deepwiki").contains("sse");
        verify(config).setMcpServer(eq("deepwiki"), argThat(map ->
                "sse".equals(map.get("type"))
                && "https://mcp.deepwiki.com/sse".equals(map.get("url"))
        ));
        verify(catalogBuilder).rebuild();
    }

    @Test
    void addSseWithoutTransportShouldAutoInfer() {
        when(config.getMcpServers()).thenReturn(Map.of());

        // 省略 --transport，有 url → 自動推斷 sse
        String result = commands.add("deepwiki", "https://mcp.deepwiki.com/sse", null, null);

        assertThat(result).contains("Added").contains("deepwiki").contains("sse");
        verify(config).setMcpServer(eq("deepwiki"), argThat(map ->
                "sse".equals(map.get("type"))
        ));
    }

    // === /mcp-add（stdio）===

    @Test
    void addStdioShouldParseExecIntoCommandAndArgs() {
        when(config.getMcpServers()).thenReturn(Map.of());

        String result = commands.add("fs", "", "stdio",
                "npx -y @modelcontextprotocol/server-filesystem /tmp");

        assertThat(result).contains("Added").contains("fs").contains("stdio");
        verify(config).setMcpServer(eq("fs"), argThat(map ->
                "stdio".equals(map.get("type"))
                && "npx".equals(map.get("command"))
                && map.get("args") instanceof List<?> argsList
                && argsList.size() == 3
                && argsList.get(0).equals("-y")
        ));
        verify(catalogBuilder).rebuild();
    }

    @Test
    void addStdioWithoutTransportShouldAutoInfer() {
        when(config.getMcpServers()).thenReturn(Map.of());

        // 省略 --transport，有 --exec → 自動推斷 stdio
        String result = commands.add("fs", "", null,
                "npx -y @modelcontextprotocol/server-filesystem /tmp");

        assertThat(result).contains("Added").contains("fs").contains("stdio");
    }

    @Test
    void addStdioExecCommandOnlyShouldWork() {
        when(config.getMcpServers()).thenReturn(Map.of());

        // --exec 只有 command，沒有 args
        String result = commands.add("simple", "", "stdio", "my-server");

        assertThat(result).contains("Added").contains("simple");
        verify(config).setMcpServer(eq("simple"), argThat(map ->
                "my-server".equals(map.get("command")) && !map.containsKey("args")
        ));
    }

    // === /mcp-add（驗證）===

    @Test
    void addWithoutNameShouldShowUsage() {
        String result = commands.add("", "", null, null);
        assertThat(result).contains("Usage:").contains("/mcp-add");
    }

    @Test
    void addWithoutTransportOrUrlOrExecShouldShowUsage() {
        String result = commands.add("test", "", null, null);
        assertThat(result).contains("Usage:");
    }

    @Test
    void addShouldRejectInvalidName() {
        String result = commands.add("bad name!", "https://example.com", "sse", null);
        assertThat(result).contains("Invalid name");
    }

    @Test
    void addShouldRejectInvalidTransport() {
        String result = commands.add("test", "", "websocket", null);
        assertThat(result).contains("Invalid transport");
    }

    @Test
    void addShouldRejectMissingUrlForSse() {
        String result = commands.add("test", "", "sse", null);
        assertThat(result).contains("URL is required");
    }

    @Test
    void addShouldRejectMissingExecForStdio() {
        String result = commands.add("test", "", "stdio", null);
        assertThat(result).contains("--exec is required");
    }

    @Test
    void addShouldRejectDuplicateName() {
        when(config.getMcpServers()).thenReturn(Map.of(
                "deepwiki", Map.of("type", "sse", "url", "https://mcp.deepwiki.com/sse")
        ));

        String result = commands.add("deepwiki", "https://other.com", "sse", null);
        assertThat(result).contains("already exists");
    }

    @Test
    void addShouldRejectInvalidUrl() {
        when(config.getMcpServers()).thenReturn(Map.of());

        String result = commands.add("test", "not-a-url", "sse", null);
        assertThat(result).contains("Invalid URL");
    }

    // === /mcp-remove ===

    @Test
    void removeShouldDeleteAndRebuild() {
        when(config.removeMcpServer("deepwiki")).thenReturn(true);

        String result = commands.remove("deepwiki");

        assertThat(result).contains("Removed").contains("deepwiki");
        verify(config).removeMcpServer("deepwiki");
        verify(catalogBuilder).rebuild();
    }

    @Test
    void removeShouldReportNotFound() {
        when(config.removeMcpServer("nonexistent")).thenReturn(false);

        String result = commands.remove("nonexistent");

        assertThat(result).contains("not found");
        verify(catalogBuilder, never()).rebuild();
    }

    @Test
    void removeWithoutNameShouldShowUsage() {
        String result = commands.remove("");
        assertThat(result).contains("Usage:").contains("/mcp-remove");
    }
}
