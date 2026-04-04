package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.config.GrimoConfig;
import io.github.samzhu.grimo.config.GrimoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.model.AgentModel;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentCommandsTest {

    private AgentModelRegistry registry;
    private GrimoConfig config;
    private GrimoProperties grimoProperties;
    private ApplicationEventPublisher eventPublisher;
    private AgentCommands commands;

    @BeforeEach
    void setUp() {
        registry = new AgentModelRegistry();
        config = mock(GrimoConfig.class);
        grimoProperties = mock(GrimoProperties.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        when(grimoProperties.getDefaults()).thenReturn(Map.of(
            "claude", "claude-sonnet-4-6",
            "gemini", "gemini-2.5-pro",
            "codex", "gpt-5.4"
        ));
        commands = new AgentCommands(registry, config, eventPublisher, grimoProperties);
    }

    private void registerAgent(String id) {
        var model = mock(AgentModel.class);
        when(model.isAvailable()).thenReturn(true);
        registry.register(id, model);
    }

    @Test
    void listShouldShowDefaultModelFromProperties() {
        registerAgent("claude");
        when(config.getDefaultAgent()).thenReturn("claude");
        when(config.getAgentOption("claude", "model")).thenReturn(null);
        String result = commands.list();
        assertThat(result).contains("claude-sonnet-4-6");
    }

    @Test
    void useShouldPassthroughModelHintDirectly() {
        registerAgent("claude");
        String result = commands.use("claude opus");
        assertThat(result).contains("opus");
    }

    @Test
    void useShouldFallbackToPropertiesDefault() {
        registerAgent("codex");
        when(config.getAgentOption("codex", "model")).thenReturn(null);
        String result = commands.use("codex");
        assertThat(result).contains("gpt-5.4");
    }

    @Test
    void useShouldRejectUnknownAgent() {
        String result = commands.use("unknown");
        assertThat(result).contains("not found");
    }

    @Test
    void useShouldReturnUsageWhenNoArgs() {
        String result = commands.use(null);
        assertThat(result).contains("Usage");
    }
}
