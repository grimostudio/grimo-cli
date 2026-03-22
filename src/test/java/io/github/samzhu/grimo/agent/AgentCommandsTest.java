package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.provider.*;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCommandsTest {

    AgentProviderRegistry registry;
    AgentCommands commands;

    @BeforeEach
    void setUp() {
        registry = new AgentProviderRegistry();
        commands = new AgentCommands(registry);
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
