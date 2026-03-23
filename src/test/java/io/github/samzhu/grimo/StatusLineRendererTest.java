package io.github.samzhu.grimo;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StatusLineRendererTest {

    @Test
    void buildStatusLineShouldContainAllSegments() {
        var renderer = new StatusLineRenderer(null); // null terminal = no-op mode
        AttributedString line = renderer.buildStatusLine(
            "anthropic", "sonnet4.5", "~/grimo-workspace", 1, 3, 2, 1);
        String plain = line.toString();
        assertThat(plain).contains("anthropic");
        assertThat(plain).contains("sonnet4.5");
        assertThat(plain).contains("~/grimo-workspace");
        assertThat(plain).contains("1 agent");
        assertThat(plain).contains("3 skill");
        assertThat(plain).contains("2 mcp");
        assertThat(plain).contains("1 task");
    }

    @Test
    void buildStatusLineShouldContainSeparators() {
        var renderer = new StatusLineRenderer(null);
        AttributedString line = renderer.buildStatusLine(
            "anthropic", "sonnet4.5", "~/test", 0, 0, 0, 0);
        String plain = line.toString();
        assertThat(plain).contains("\u2502");
        assertThat(plain).contains("\u00b7");
    }

    @Test
    void updateShouldBeNoOpWhenTerminalIsNull() {
        var renderer = new StatusLineRenderer(null);
        renderer.update("anthropic", "sonnet4.5", "~/test", 0, 0, 0, 0);
        renderer.suspend();
        renderer.restore();
    }
}
