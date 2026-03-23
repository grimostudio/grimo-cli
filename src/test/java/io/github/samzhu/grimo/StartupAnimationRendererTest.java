package io.github.samzhu.grimo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StartupAnimationRendererTest {

    @Test
    void isAnimationSupportedShouldReturnFalseForDumbTerminal() {
        assertThat(StartupAnimationRenderer.isAnimationSupported("dumb")).isFalse();
    }

    @Test
    void isAnimationSupportedShouldReturnTrueForXterm() {
        assertThat(StartupAnimationRenderer.isAnimationSupported("xterm-256color")).isTrue();
    }

    @Test
    void isAnimationSupportedShouldReturnFalseForNull() {
        assertThat(StartupAnimationRenderer.isAnimationSupported(null)).isFalse();
    }

    @Test
    void formatLoadingStepSuccessShouldContainCheckMark() {
        String result = StartupAnimationRenderer.formatLoadingStep(
            "Detecting agents", "claude-cli", true);
        assertThat(result).contains("Detecting agents");
        assertThat(result).contains("claude-cli");
        assertThat(result).contains("\u2713");
    }

    @Test
    void formatLoadingStepFailureShouldContainCrossMark() {
        String result = StartupAnimationRenderer.formatLoadingStep(
            "Connecting MCP", "connection refused", false);
        assertThat(result).contains("Connecting MCP");
        assertThat(result).contains("\u2717");
    }
}
