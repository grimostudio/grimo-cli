package io.github.samzhu.grimo.tui.widget;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BannerTest {

    @Test
    void renderShouldContainVersionAndAgentInfo() {
        var renderer = new Banner();
        String banner = renderer.render("0.1.0", "claude-cli", "sonnet",
            "~/workspace/grimo-cli", 1, 3, 2, 1, 80);
        assertThat(banner).contains("Grimo");
        assertThat(banner).contains("0.1.0");
        assertThat(banner).contains("claude-cli");
        assertThat(banner).contains("~/workspace/grimo-cli");
        assertThat(banner).contains("1 agent");
        assertThat(banner).contains("3 skill");
        assertThat(banner).contains("2 mcp");
        assertThat(banner).contains("1 task");
    }

    @Test
    void renderShouldContainMascotCharacters() {
        var renderer = new Banner();
        String banner = renderer.render("dev", "none", "none", "~/test", 0, 0, 0, 0, 80);
        assertThat(banner).contains("▄");
        assertThat(banner).contains("█");
        assertThat(banner).contains("▀");
        assertThat(banner).contains("✦");
    }

    @Test
    void renderShouldHandleDevVersion() {
        var renderer = new Banner();
        String banner = renderer.render("dev", "no agent", "unknown", "~/test", 0, 0, 0, 0, 80);
        assertThat(banner).contains("dev");
        assertThat(banner).contains("no agent");
    }
}
