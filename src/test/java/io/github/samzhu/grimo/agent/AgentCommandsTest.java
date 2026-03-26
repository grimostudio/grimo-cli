package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agents.model.AgentModel;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentCommandsTest {

    @TempDir
    Path tempDir;

    AgentModelRegistry registry;
    AgentCommands commands;

    @BeforeEach
    void setUp() {
        registry = new AgentModelRegistry();
        var config = new GrimoConfig(tempDir.resolve("config.yaml"));
        commands = new AgentCommands(registry, config);
    }

    @Test
    void listShowsRegisteredAgents() {
        var claude = mock(AgentModel.class);
        when(claude.isAvailable()).thenReturn(true);
        var gemini = mock(AgentModel.class);
        when(gemini.isAvailable()).thenReturn(false);
        registry.register("claude", claude);
        registry.register("gemini", gemini);

        String output = commands.list();

        assertThat(output).contains("claude").contains("ready");
        assertThat(output).contains("gemini").contains("not available");
    }

    @Test
    void listShowsEmptyMessage() {
        assertThat(commands.list()).contains("No agents available");
    }

    @Test
    void useSwitchesDefaultAgent() {
        registry.register("claude", mock(AgentModel.class));
        assertThat(commands.use("claude")).contains("claude");
    }

    @Test
    void useRejectsUnknownAgent() {
        assertThat(commands.use("nonexistent")).contains("not found");
    }

    @Test
    void modelSwitchesModel() {
        assertThat(commands.model("claude-opus-4-0")).contains("claude-opus-4-0");
    }
}
