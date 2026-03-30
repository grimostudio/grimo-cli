package io.github.samzhu.grimo.agent.tier;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.claude.ClaudeAgentOptions;
import org.springaicommunity.agents.codex.CodexAgentOptions;
import org.springaicommunity.agents.gemini.GeminiAgentOptions;
import org.springaicommunity.agents.model.AgentOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TierOptionsFactoryTest {

    private final TierOptionsFactory factory = new TierOptionsFactory();

    @Test
    void buildClaudeOptions() {
        AgentOptions options = factory.build("claude", "claude-haiku-4");
        assertThat(options).isInstanceOf(ClaudeAgentOptions.class);
        assertThat(options.getModel()).isEqualTo("claude-haiku-4");
    }

    @Test
    void buildGeminiOptions() {
        AgentOptions options = factory.build("gemini", "gemini-2.5-flash");
        assertThat(options).isInstanceOf(GeminiAgentOptions.class);
        assertThat(options.getModel()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void buildCodexOptions() {
        AgentOptions options = factory.build("codex", "o4-mini");
        assertThat(options).isInstanceOf(CodexAgentOptions.class);
        assertThat(options.getModel()).isEqualTo("o4-mini");
    }

    @Test
    void buildUnknownAgentThrows() {
        assertThatThrownBy(() -> factory.build("unknown", "model"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }
}
