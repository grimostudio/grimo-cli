package io.github.samzhu.grimo.shared.tui;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DisplayWidthTest {

    @Test
    void ofShouldReturnZeroForEmptyString() {
        assertThat(DisplayWidth.of("")).isEqualTo(0);
    }

    @Test
    void ofShouldCountAsciiAsSingleWidth() {
        assertThat(DisplayWidth.of("hello")).isEqualTo(5);
    }

    @Test
    void ofShouldCountCjkAsDoubleWidth() {
        assertThat(DisplayWidth.of("你好")).isEqualTo(4);
    }

    @Test
    void ofShouldHandleMixedAsciiAndCjk() {
        assertThat(DisplayWidth.of("hi你好")).isEqualTo(6);
    }

    @Test
    void padRightShouldPadAsciiToWidth() {
        assertThat(DisplayWidth.padRight("hi", 5)).isEqualTo("hi   ");
        assertThat(DisplayWidth.of(DisplayWidth.padRight("hi", 5))).isEqualTo(5);
    }

    @Test
    void padRightShouldPadCjkToWidth() {
        String result = DisplayWidth.padRight("你好", 6);
        assertThat(DisplayWidth.of(result)).isEqualTo(6);
        assertThat(result).isEqualTo("你好  ");
    }

    @Test
    void padRightShouldTruncateIfTooWide() {
        String result = DisplayWidth.padRight("hello world", 5);
        assertThat(DisplayWidth.of(result)).isLessThanOrEqualTo(5);
    }

    @Test
    void padLeftShouldRightAlignText() {
        assertThat(DisplayWidth.padLeft("hi", 5)).isEqualTo("   hi");
    }

    @Test
    void centerShouldCenterText() {
        String result = DisplayWidth.center("hi", 6);
        assertThat(result).isEqualTo("  hi  ");
        assertThat(DisplayWidth.of(result)).isEqualTo(6);
    }

    @Test
    void centerShouldHandleOddWidth() {
        String result = DisplayWidth.center("hi", 7);
        assertThat(DisplayWidth.of(result)).isEqualTo(7);
        assertThat(result.trim()).isEqualTo("hi");
    }

    @Test
    void truncateShouldNotTruncateShortString() {
        assertThat(DisplayWidth.truncate("hi", 10)).isEqualTo("hi");
    }

    @Test
    void truncateShouldAddEllipsisForLongString() {
        String result = DisplayWidth.truncate("hello world", 8);
        assertThat(DisplayWidth.of(result)).isLessThanOrEqualTo(8);
        assertThat(result).endsWith("…");
    }

    @Test
    void truncateShouldNotSplitCjkCharacter() {
        String result = DisplayWidth.truncate("你好世界", 5);
        assertThat(DisplayWidth.of(result)).isLessThanOrEqualTo(5);
        assertThat(result).endsWith("…");
    }

    @Test
    void fillShouldReturnSpaces() {
        assertThat(DisplayWidth.fill(3)).isEqualTo("   ");
        assertThat(DisplayWidth.of(DisplayWidth.fill(3))).isEqualTo(3);
    }

    @Test
    void wrapShouldNotWrapShortString() {
        var lines = DisplayWidth.wrap("hello", 10);
        assertThat(lines).containsExactly("hello");
    }

    @Test
    void wrapShouldWrapLongString() {
        var lines = DisplayWidth.wrap("hello world", 6);
        assertThat(lines).hasSize(2);
        for (String line : lines) {
            assertThat(DisplayWidth.of(line)).isLessThanOrEqualTo(6);
        }
    }

    @Test
    void wrapShouldHandleCjk() {
        var lines = DisplayWidth.wrap("你好世界", 5);
        assertThat(lines.size()).isGreaterThanOrEqualTo(2);
        for (String line : lines) {
            assertThat(DisplayWidth.of(line)).isLessThanOrEqualTo(5);
        }
    }
}
