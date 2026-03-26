package io.github.samzhu.grimo.mcp;

import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpCommandsTest {

    GrimoConfig config;
    McpCommands commands;

    @BeforeEach
    void setUp() {
        config = mock(GrimoConfig.class);
        commands = new McpCommands(config);
    }

    @Test
    void listShouldShowConfiguredServers() {
        when(config.getMcpServers()).thenReturn(Map.of(
                "github", Map.of("type", "stdio", "command", "npx server-github")
        ));

        String output = commands.list();

        assertThat(output).contains("github");
        assertThat(output).contains("stdio");
        assertThat(output).contains("npx server-github");
    }

    @Test
    void listShouldShowEmptyMessage() {
        when(config.getMcpServers()).thenReturn(Map.of());

        assertThat(commands.list()).contains("No MCP servers configured");
    }

    @Test
    void listShouldShowSseServer() {
        when(config.getMcpServers()).thenReturn(Map.of(
                "weather", Map.of("type", "sse", "url", "http://localhost:8080/sse")
        ));

        String output = commands.list();

        assertThat(output).contains("weather");
        assertThat(output).contains("sse");
        assertThat(output).contains("http://localhost:8080/sse");
    }
}
