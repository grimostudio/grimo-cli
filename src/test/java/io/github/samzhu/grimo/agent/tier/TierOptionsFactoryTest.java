package io.github.samzhu.grimo.agent.tier;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.claude.ClaudeAgentOptions;
import org.springaicommunity.agents.codex.CodexAgentOptions;
import org.springaicommunity.agents.gemini.GeminiAgentOptions;

import static org.assertj.core.api.Assertions.assertThat;

class TierOptionsFactoryTest {

    private final TierOptionsFactory factory = new TierOptionsFactory();

    @Test
    void planModeShouldDisallowEditToolsForClaude() {
        var options = (ClaudeAgentOptions) factory.build("claude", "claude-sonnet-4-6",
                TierOptionsFactory.ExecutionMode.PLAN);
        assertThat(options.getDisallowedTools()).contains("Edit", "Write", "MultiEdit");
    }

    @Test
    void planModeShouldRestrictCodex() {
        var options = (CodexAgentOptions) factory.build("codex", "o4-mini",
                TierOptionsFactory.ExecutionMode.PLAN);
        assertThat(options).isNotNull();
        assertThat(options.isFullAuto()).isFalse();
    }

    @Test
    void planModeShouldRestrictGemini() {
        var options = (GeminiAgentOptions) factory.build("gemini", "gemini-2.5-pro",
                TierOptionsFactory.ExecutionMode.PLAN);
        assertThat(options).isNotNull();
        assertThat(options.isYolo()).isFalse();
    }

    @Test
    void devModeShouldAllowAllToolsForClaude() {
        var options = (ClaudeAgentOptions) factory.build("claude", "claude-sonnet-4-6",
                TierOptionsFactory.ExecutionMode.DEV);
        assertThat(options.getDisallowedTools()).isNullOrEmpty();
    }

    @Test
    void devModeShouldBeFullAccessForCodex() {
        var options = (CodexAgentOptions) factory.build("codex", "o4-mini",
                TierOptionsFactory.ExecutionMode.DEV);
        assertThat(options.isFullAuto()).isTrue();
    }

    @Test
    void devModeShouldBeYoloForGemini() {
        var options = (GeminiAgentOptions) factory.build("gemini", "gemini-2.5-pro",
                TierOptionsFactory.ExecutionMode.DEV);
        assertThat(options.isYolo()).isTrue();
    }

    @Test
    void legacyBuildShouldDefaultToDev() {
        var options = (ClaudeAgentOptions) factory.build("claude", "claude-sonnet-4-6");
        assertThat(options.getDisallowedTools()).isNullOrEmpty();
    }
}
