package io.github.samzhu.grimo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BannerRendererTest {

    @Test
    void renderShouldContainVersionAndAgentInfo() {
        var renderer = new BannerRenderer();
        String banner = renderer.render("0.1.0", "claude-cli", "sonnet",
            "~/workspace/grimo-cli", 3, 2, 1);
        assertThat(banner).contains("Grimo");
        assertThat(banner).contains("0.1.0");
        assertThat(banner).contains("claude-cli");
        assertThat(banner).contains("sonnet");
        assertThat(banner).contains("~/workspace/grimo-cli");
        assertThat(banner).contains("3 skills");
        assertThat(banner).contains("2 mcp");
        assertThat(banner).contains("1 task");
    }

    @Test
    void renderShouldContainMascotCharacters() {
        var renderer = new BannerRenderer();
        String banner = renderer.render("dev", "none", "none", "~/test", 0, 0, 0);
        assertThat(banner).contains("▄");
        assertThat(banner).contains("█");
        assertThat(banner).contains("▀");
        assertThat(banner).contains("✦");
    }

    @Test
    void renderShouldHandleDevVersion() {
        var renderer = new BannerRenderer();
        String banner = renderer.render("dev", "no agent", "unknown", "~/test", 0, 0, 0);
        assertThat(banner).contains("dev");
        assertThat(banner).contains("no agent");
    }
}
