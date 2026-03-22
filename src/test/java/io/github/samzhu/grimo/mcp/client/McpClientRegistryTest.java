package io.github.samzhu.grimo.mcp.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpClientRegistryTest {

    McpClientRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new McpClientRegistry();
    }

    @Test
    void registerAndGetConnection() {
        var info = new McpConnectionInfo("github", "stdio", "npx @modelcontextprotocol/server-github", 0);
        registry.register("github", info);

        assertThat(registry.get("github")).isPresent();
        assertThat(registry.get("github").get().name()).isEqualTo("github");
    }

    @Test
    void removeConnection() {
        registry.register("github", new McpConnectionInfo("github", "stdio", "cmd", 0));
        registry.remove("github");

        assertThat(registry.get("github")).isEmpty();
    }

    @Test
    void listAllConnections() {
        registry.register("a", new McpConnectionInfo("a", "stdio", "cmd-a", 0));
        registry.register("b", new McpConnectionInfo("b", "sse", "http://localhost:3001/sse", 5));

        assertThat(registry.listAll()).hasSize(2);
    }
}
