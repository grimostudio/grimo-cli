package io.github.samzhu.grimo.tui.view;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatusViewTest {

    @Test
    void renderWithoutTierShouldShowPlainText() {
        var view = new StatusView("claude · sonnet-4 │ ~/project");
        var lines = view.render(80);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().toString()).contains("claude");
    }

    @Test
    void renderWithTierShouldIncludeIcon() {
        var view = new StatusView("pro · claude · opus-4 │ ~/project");
        view.setTierDisplay("\uD83E\uDDE0", "pro");
        var lines = view.render(80);
        assertThat(lines).hasSize(1);
    }

    @Test
    void renderClearedTierShouldRevertToPlain() {
        var view = new StatusView("claude · sonnet-4");
        view.setTierDisplay("⚡", "lite");
        view.setTierDisplay(null, null); // clear
        var lines = view.render(80);
        assertThat(lines).hasSize(1);
    }
}
