package io.github.samzhu.grimo.agent.detect;

import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDetectorTest {

    @Test
    void detectShouldReturnDetectionResults() {
        var registry = new AgentProviderRegistry();
        var detector = new AgentDetector(registry);

        var results = detector.detect();

        assertThat(results).isNotNull();
        assertThat(results).isNotEmpty();
    }

    @Test
    void detectShouldCheckForAnthropicApiKey() {
        var registry = new AgentProviderRegistry();
        var detector = new AgentDetector(registry);

        var results = detector.detect();

        assertThat(results.stream()
            .anyMatch(r -> r.id().equals("anthropic"))).isTrue();
    }

    @Test
    void detectShouldCheckForClaudeCli() {
        var registry = new AgentProviderRegistry();
        var detector = new AgentDetector(registry);

        var results = detector.detect();

        assertThat(results.stream()
            .anyMatch(r -> r.id().equals("claude-cli"))).isTrue();
    }
}
