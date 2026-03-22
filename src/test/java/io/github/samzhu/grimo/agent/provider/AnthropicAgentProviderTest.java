package io.github.samzhu.grimo.agent.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicAgentProviderTest {

    @Test
    void shouldReportCorrectIdAndType() {
        var provider = new AnthropicAgentProvider("test-key", "claude-sonnet-4");

        assertThat(provider.id()).isEqualTo("anthropic");
        assertThat(provider.type()).isEqualTo(AgentType.API);
    }

    @Test
    void isAvailableShouldReturnTrueWhenApiKeyProvided() {
        var provider = new AnthropicAgentProvider("sk-ant-test", "claude-sonnet-4");
        assertThat(provider.isAvailable()).isTrue();
    }

    @Test
    void isAvailableShouldReturnFalseWhenApiKeyBlank() {
        var provider = new AnthropicAgentProvider("", "claude-sonnet-4");
        assertThat(provider.isAvailable()).isFalse();
    }
}
