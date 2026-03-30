package io.github.samzhu.grimo.shared.tui;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class TuiSelectorTest {

    @Test
    void shouldRenderItemsWithCorrectWidth() {
        var lines = TuiSelector.render(
                List.of("code-review", "explain-code"),
                0, 5, 40);
        assertThat(lines).hasSize(2);
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(40);
        }
    }

    @Test
    void shouldHighlightSelectedItem() {
        var lines = TuiSelector.render(
                List.of("item-a", "item-b", "item-c"),
                1, 3, 30);
        assertThat(lines).hasSize(3);
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(30);
        }
    }

    @Test
    void shouldLimitVisibleItems() {
        var lines = TuiSelector.render(
                List.of("a", "b", "c", "d", "e", "f"),
                0, 3, 20);
        assertThat(lines).hasSize(3);
    }

    @Test
    void shouldHandleEmptyList() {
        var lines = TuiSelector.render(List.of(), 0, 5, 20);
        assertThat(lines).isEmpty();
    }

    @Test
    void shouldHandleCjkItems() {
        var lines = TuiSelector.render(
                List.of("你好", "hello"),
                0, 5, 30);
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(30);
        }
    }
}
