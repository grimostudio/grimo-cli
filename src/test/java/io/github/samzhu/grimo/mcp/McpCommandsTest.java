package io.github.samzhu.grimo.mcp;

import io.github.samzhu.grimo.mcp.client.McpClientRegistry;
import io.github.samzhu.grimo.mcp.client.McpConnectionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpCommandsTest {

    McpClientRegistry registry;
    McpCommands commands;

    @BeforeEach
    void setUp() {
        registry = new McpClientRegistry();
        commands = new McpCommands(registry);
    }

    @Test
    void listShouldShowConnections() {
        registry.register("github", new McpConnectionInfo("github", "stdio", "npx server-github", 12));

        String output = commands.list();

        assertThat(output).contains("github");
        assertThat(output).contains("stdio");
        assertThat(output).contains("12");
    }

    @Test
    void listShouldShowEmptyMessage() {
        assertThat(commands.list()).contains("No MCP connections");
    }

    @Test
    void removeShouldRemoveExistingConnection() {
        registry.register("github", new McpConnectionInfo("github", "stdio", "npx server-github", 12));

        String output = commands.remove("github");

        assertThat(output).contains("MCP connection removed: github");
        assertThat(registry.get("github")).isEmpty();
    }

    @Test
    void removeShouldReturnNotFoundForMissingConnection() {
        String output = commands.remove("nonexistent");

        assertThat(output).contains("MCP connection not found: nonexistent");
    }
}
