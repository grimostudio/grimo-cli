package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.provider.*;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCommandsTest {

    @TempDir
    Path tempDir;

    AgentProviderRegistry registry;
    AgentCommands commands;

    @BeforeEach
    void setUp() {
        registry = new AgentProviderRegistry();
        var config = new GrimoConfig(tempDir.resolve("config.yaml"));
        commands = new AgentCommands(registry, config);
    }

    @Test
    void listShouldReturnFormattedTable() {
        registry.register("anthropic", stubProvider("anthropic", AgentType.API, true));
        registry.register("claude-cli", stubProvider("claude-cli", AgentType.CLI, true));

        String output = commands.list();

        assertThat(output).contains("anthropic");
        assertThat(output).contains("claude-cli");
        assertThat(output).contains("ready");
    }

    @Test
    void listShouldShowEmptyMessage() {
        String output = commands.list();
        assertThat(output).contains("No agents configured");
    }

    @Test
    void useShouldSwitchDefaultAgent() {
        registry.register("openai", stubProvider("openai", AgentType.API, true));

        String result = commands.use("openai");

        assertThat(result).contains("openai");
    }

    @Test
    void useShouldRejectUnknownAgent() {
        String result = commands.use("nonexistent");

        assertThat(result).contains("not found");
    }

    @Test
    void modelShouldSwitchDefaultModel() {
        String result = commands.model("gpt-4o");

        assertThat(result).contains("gpt-4o");
    }

    private AgentProvider stubProvider(String id, AgentType type, boolean available) {
        return new AgentProvider() {
            @Override public String id() { return id; }
            @Override public AgentType type() { return type; }
            @Override public boolean isAvailable() { return available; }
            @Override public AgentResult execute(AgentRequest request) {
                return new AgentResult(true, "stub");
            }
        };
    }
}
