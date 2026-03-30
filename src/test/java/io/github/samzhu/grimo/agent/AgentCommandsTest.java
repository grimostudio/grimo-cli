package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agents.model.AgentModel;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCommandsTest {

    @TempDir Path tempDir;
    private GrimoConfig config;
    private AgentModelRegistry registry;
    private AgentCommands commands;

    @BeforeEach
    void setUp() throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "agents:\n  default: claude\n");
        config = new GrimoConfig(configFile);
        registry = new AgentModelRegistry();

        // Register mock agents
        var claudeModel = mock(AgentModel.class);
        when(claudeModel.isAvailable()).thenReturn(true);
        registry.register("claude", claudeModel);

        var geminiModel = mock(AgentModel.class);
        when(geminiModel.isAvailable()).thenReturn(true);
        registry.register("gemini", geminiModel);

        var codexModel = mock(AgentModel.class);
        when(codexModel.isAvailable()).thenReturn(true);
        registry.register("codex", codexModel);

        commands = new AgentCommands(registry, config);
    }

    @Test
    void useShouldSetRecommendedModelWhenNoModelSpecified() {
        String result = commands.use("claude");
        assertThat(result).contains("claude").contains("claude-sonnet-4-6");
        assertThat(config.getDefaultAgent()).isEqualTo("claude");
        assertThat(config.getDefaultModel()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void useShouldSetRecommendedModelForGemini() {
        String result = commands.use("gemini");
        assertThat(result).contains("gemini").contains("gemini-2.5-pro");
        assertThat(config.getDefaultModel()).isEqualTo("gemini-2.5-pro");
    }

    @Test
    void useShouldSetRecommendedModelForCodex() {
        String result = commands.use("codex");
        assertThat(result).contains("codex").contains("o4-mini");
        assertThat(config.getDefaultModel()).isEqualTo("o4-mini");
    }

    @Test
    void useShouldSmartMatchAlias() {
        String result = commands.use("claude opus");
        assertThat(result).contains("claude-opus-4-6");
        assertThat(config.getDefaultModel()).isEqualTo("claude-opus-4-6");
        assertThat(config.getAgentOption("claude", "model")).isEqualTo("claude-opus-4-6");
    }

    @Test
    void useShouldSmartMatchGeminiFlash() {
        String result = commands.use("gemini flash");
        assertThat(result).contains("gemini-2.5-flash");
        assertThat(config.getAgentOption("gemini", "model")).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void useShouldRememberModelAcrossSwitches() {
        // 設定 claude 用 opus
        commands.use("claude opus");
        assertThat(config.getDefaultModel()).isEqualTo("claude-opus-4-6");

        // 切到 gemini
        commands.use("gemini");
        assertThat(config.getDefaultModel()).isEqualTo("gemini-2.5-pro");

        // 切回 claude — 應該記住 opus
        commands.use("claude");
        assertThat(config.getDefaultModel()).isEqualTo("claude-opus-4-6");
    }

    @Test
    void useShouldAcceptFullModelId() {
        String result = commands.use("claude claude-opus-4-6");
        assertThat(result).contains("claude-opus-4-6");
        assertThat(config.getDefaultModel()).isEqualTo("claude-opus-4-6");
    }

    @Test
    void useShouldRejectUnknownAgent() {
        String result = commands.use("unknown");
        assertThat(result).contains("not found");
    }
}
