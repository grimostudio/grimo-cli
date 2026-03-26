package io.github.samzhu.grimo.mcp;

import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.model.mcp.McpServerDefinition;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpCatalogBuilderTest {

    @Test
    void buildsCatalogFromConfig() {
        var config = mock(GrimoConfig.class);
        when(config.getMcpServers()).thenReturn(Map.of(
                "brave-search", Map.of(
                        "type", "stdio",
                        "command", "npx",
                        "args", List.of("-y", "@modelcontextprotocol/server-brave-search")
                ),
                "weather", Map.of(
                        "type", "sse",
                        "url", "http://localhost:8080/sse"
                )
        ));

        var builder = new McpCatalogBuilder(config);
        var catalog = builder.build();

        assertThat(catalog.getAll()).hasSize(2);
        assertThat(catalog.contains("brave-search")).isTrue();
        assertThat(catalog.contains("weather")).isTrue();
    }

    @Test
    void returnsEmptyCatalogWhenNoMcpConfig() {
        var config = mock(GrimoConfig.class);
        when(config.getMcpServers()).thenReturn(Map.of());

        var builder = new McpCatalogBuilder(config);
        var catalog = builder.build();

        assertThat(catalog.getAll()).isEmpty();
    }

    @Test
    void buildsStdioDefinitionWithEnv() {
        var config = mock(GrimoConfig.class);
        when(config.getMcpServers()).thenReturn(Map.of(
                "my-tool", Map.of(
                        "type", "stdio",
                        "command", "python3",
                        "args", List.of("server.py"),
                        "env", Map.of("API_KEY", "secret")
                )
        ));

        var catalog = new McpCatalogBuilder(config).build();

        assertThat(catalog.contains("my-tool")).isTrue();
        var def = catalog.getAll().get("my-tool");
        assertThat(def).isInstanceOf(McpServerDefinition.StdioDefinition.class);
        var stdio = (McpServerDefinition.StdioDefinition) def;
        assertThat(stdio.command()).isEqualTo("python3");
        assertThat(stdio.args()).containsExactly("server.py");
        assertThat(stdio.env()).containsEntry("API_KEY", "secret");
    }

    @Test
    void buildsHttpDefinition() {
        var config = mock(GrimoConfig.class);
        when(config.getMcpServers()).thenReturn(Map.of(
                "files", Map.of(
                        "type", "http",
                        "url", "http://localhost:9090/mcp"
                )
        ));

        var catalog = new McpCatalogBuilder(config).build();

        assertThat(catalog.contains("files")).isTrue();
        var def = catalog.getAll().get("files");
        assertThat(def).isInstanceOf(McpServerDefinition.HttpDefinition.class);
        var http = (McpServerDefinition.HttpDefinition) def;
        assertThat(http.url()).isEqualTo("http://localhost:9090/mcp");
    }

    @Test
    void throwsOnUnknownType() {
        var config = mock(GrimoConfig.class);
        when(config.getMcpServers()).thenReturn(Map.of(
                "bad-server", Map.of(
                        "type", "websocket",
                        "url", "ws://localhost:1234"
                )
        ));

        var builder = new McpCatalogBuilder(config);
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("websocket");
    }

    @Test
    void throwsWhenStdioMissingCommand() {
        var config = mock(GrimoConfig.class);
        when(config.getMcpServers()).thenReturn(Map.of(
                "no-cmd", Map.of("type", "stdio")
        ));

        var builder = new McpCatalogBuilder(config);
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");
    }
}
