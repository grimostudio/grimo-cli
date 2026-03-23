package io.github.samzhu.grimo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GrimoPromptProviderTest {

    @Test
    void promptShouldBeMinimalArrow() {
        var provider = new GrimoPromptProvider();
        var prompt = provider.getPrompt();
        assertThat(prompt.toString()).isEqualTo("❯ ");
    }
}
