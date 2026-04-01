package io.github.samzhu.grimo.tui.widget;

import io.github.samzhu.grimo.tui.core.DisplayWidth;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TableTest {

    @Test
    void shouldAlignColumnsWithFixedWidth() {
        String result = Table.builder()
                .column("ID", 10)
                .column("STATUS", 10)
                .row("claude", "ready")
                .row("gemini", "ready")
                .build(30);

        var lines = result.split("\n");
        assertThat(lines).hasSize(2);
        for (String line : lines) {
            assertThat(DisplayWidth.of(line)).isEqualTo(30);
        }
    }

    @Test
    void shouldFillRemainingWidth() {
        String result = Table.builder()
                .column("", 2)
                .column("ID", 10)
                .column("MODEL", 0)
                .row("> ", "claude", "claude-sonnet-4-6")
                .row("  ", "gemini", "gemini-2.5-pro")
                .build(40);

        var lines = result.split("\n");
        for (String line : lines) {
            assertThat(DisplayWidth.of(line)).isEqualTo(40);
        }
        assertThat(lines[0]).contains("claude-sonnet-4-6");
    }

    @Test
    void shouldTruncateLongValues() {
        String result = Table.builder()
                .column("NAME", 8)
                .row("very-long-name-here")
                .build(10);

        var lines = result.split("\n");
        assertThat(DisplayWidth.of(lines[0])).isEqualTo(10);
    }

    @Test
    void shouldHandleCjkContent() {
        String result = Table.builder()
                .column("名前", 8)
                .column("狀態", 8)
                .row("你好", "正常")
                .build(20);

        var lines = result.split("\n");
        for (String line : lines) {
            assertThat(DisplayWidth.of(line)).isEqualTo(20);
        }
    }

    @Test
    void shouldHandleMultipleFixedAndOneFill() {
        String result = Table.builder()
                .column("", 2)
                .column("ID", 12)
                .column("STATUS", 12)
                .column("MODEL", 0)
                .row("> ", "claude", "ready", "claude-sonnet-4-6")
                .row("  ", "gemini", "ready", "gemini-2.5-pro")
                .row("  ", "codex", "ready", "o4-mini")
                .build(60);

        var lines = result.split("\n");
        assertThat(lines).hasSize(3);
        for (String line : lines) {
            assertThat(DisplayWidth.of(line)).isEqualTo(60);
        }
        assertThat(lines[0]).startsWith("> ");
        assertThat(lines[1]).startsWith("  ");
    }
}
