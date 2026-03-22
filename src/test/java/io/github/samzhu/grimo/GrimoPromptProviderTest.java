package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.provider.*;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GrimoPromptProviderTest {

    @Test
    void promptShouldShowAgentIdWhenAvailable() {
        var registry = new AgentProviderRegistry();
        registry.register("anthropic", stubProvider("anthropic", true));
        var provider = new GrimoPromptProvider(registry);

        var prompt = provider.getPrompt();

        assertThat(prompt.toAnsi()).contains("anthropic");
        assertThat(prompt.toAnsi()).contains("grimo:>");
    }

    @Test
    void promptShouldShowNoAgentWhenNoneAvailable() {
        var registry = new AgentProviderRegistry();
        var provider = new GrimoPromptProvider(registry);

        var prompt = provider.getPrompt();

        assertThat(prompt.toAnsi()).contains("no agent");
        assertThat(prompt.toAnsi()).contains("grimo:>");
    }

    @TempDir
    Path tempDir;

    @Test
    void promptShouldShowConfigDefaultAgent() throws IOException {
        var registry = new AgentProviderRegistry();
        registry.register("claude-cli", stubProvider("claude-cli", true));
        registry.register("anthropic", stubProvider("anthropic", true));

        var configFile = tempDir.resolve("config.yaml");
        java.nio.file.Files.writeString(configFile, "agents:\n  default: anthropic\n");
        var config = new io.github.samzhu.grimo.shared.config.GrimoConfig(configFile);

        var provider = new GrimoPromptProvider(registry, config);
        var prompt = provider.getPrompt();

        assertThat(prompt.toAnsi()).contains("anthropic");
    }

    private AgentProvider stubProvider(String id, boolean available) {
        return new AgentProvider() {
            @Override public String id() { return id; }
            @Override public AgentType type() { return AgentType.API; }
            @Override public boolean isAvailable() { return available; }
            @Override public AgentResult execute(AgentRequest request) {
                return new AgentResult(true, "stub");
            }
        };
    }
}
