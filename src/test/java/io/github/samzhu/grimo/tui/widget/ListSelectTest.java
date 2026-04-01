package io.github.samzhu.grimo.tui.widget;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ListSelectTest {

    private static <T> ListSelect.Item<T> item(String label, String desc, T val) {
        return new ListSelect.Item<>(label, desc, val);
    }

    @Test void renderEmptyList() {
        var ls = new ListSelect<String>(List.of(), 7);
        assertThat(ls.render(40)).isEmpty();
        assertThat(ls.isEmpty()).isTrue();
        assertThat(ls.getSelected()).isNull();
    }

    @Test void renderSingleItem() {
        var ls = new ListSelect<>(List.of(item("claude", "Sonnet", "c")), 7);
        var lines = ls.render(40);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().toString()).startsWith(">");
        assertThat(lines.getFirst().toString()).contains("claude");
    }

    @Test void renderWithinMaxVisible() {
        var items = List.of(item("a","d1","1"), item("b","d2","2"), item("c","d3","3"));
        var ls = new ListSelect<>(items, 7);
        var lines = ls.render(40);
        assertThat(lines).hasSize(3);
        // No scroll hints
        assertThat(lines.stream().noneMatch(l -> l.toString().contains("more"))).isTrue();
    }

    @Test void renderExceedMaxVisible() {
        var items = List.of(
            item("a","","1"), item("b","","2"), item("c","","3"),
            item("d","","4"), item("e","","5"), item("f","","6"),
            item("g","","7"), item("h","","8"), item("i","","9"));
        var ls = new ListSelect<>(items, 7);
        var lines = ls.render(40);
        assertThat(lines).hasSize(7);
    }

    @Test void moveDownUpdatesSelection() {
        var items = List.of(item("a","","1"), item("b","","2"));
        var ls = new ListSelect<>(items, 7);
        assertThat(ls.getSelectedIndex()).isEqualTo(0);
        ls.moveDown();
        assertThat(ls.getSelectedIndex()).isEqualTo(1);
    }

    @Test void moveUpUpdatesSelection() {
        var items = List.of(item("a","","1"), item("b","","2"));
        var ls = new ListSelect<>(items, 7);
        ls.moveDown();
        ls.moveUp();
        assertThat(ls.getSelectedIndex()).isEqualTo(0);
    }

    @Test void linearNavigationNoWrap() {
        var items = List.of(item("a","","1"), item("b","","2"));
        var ls = new ListSelect<>(items, 7);
        ls.moveUp(); // already at top
        assertThat(ls.getSelectedIndex()).isEqualTo(0);
        ls.moveDown();
        ls.moveDown(); // already at bottom
        assertThat(ls.getSelectedIndex()).isEqualTo(1);
    }

    @Test void moveDownScrollsViewport() {
        var items = List.of(
            item("a","","1"), item("b","","2"), item("c","","3"),
            item("d","","4"), item("e","","5"), item("f","","6"),
            item("g","","7"), item("h","","8"));
        var ls = new ListSelect<>(items, 4);
        // Move to bottom of viewport
        for (int i = 0; i < 7; i++) ls.moveDown();
        assertThat(ls.getSelectedIndex()).isEqualTo(7);
        var lines = ls.render(40);
        assertThat(lines).hasSize(4);
        // Last item should be visible (selected)
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("h"))).isTrue();
    }

    @Test void moveUpScrollsViewport() {
        var items = List.of(
            item("a","","1"), item("b","","2"), item("c","","3"),
            item("d","","4"), item("e","","5"));
        var ls = new ListSelect<>(items, 4);
        for (int i = 0; i < 4; i++) ls.moveDown();
        for (int i = 0; i < 4; i++) ls.moveUp();
        assertThat(ls.getSelectedIndex()).isEqualTo(0);
        var lines = ls.render(40);
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("a"))).isTrue();
    }

    @Test void scrollHintShowsCount() {
        var items = List.of(
            item("a","","1"), item("b","","2"), item("c","","3"),
            item("d","","4"), item("e","","5"));
        var ls = new ListSelect<>(items, 3);
        ls.moveDown(); // select b, should have ↑ 1 and ↓ hints
        var lines = ls.render(40);
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("↑"))).isTrue();
    }

    @Test void scrollHintOnlyBottomWhenAtTop() {
        var items = List.of(
            item("a","","1"), item("b","","2"), item("c","","3"),
            item("d","","4"), item("e","","5"));
        var ls = new ListSelect<>(items, 3);
        // At top, should only have ↓ hint
        var lines = ls.render(40);
        assertThat(lines.stream().noneMatch(l -> l.toString().contains("↑"))).isTrue();
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("↓"))).isTrue();
    }

    @Test void descriptionRenderedInGray() {
        var ls = new ListSelect<>(List.of(item("agent", "desc here", "v")), 7);
        var lines = ls.render(60);
        // description should be present
        assertThat(lines.getFirst().toString()).contains("desc here");
    }

    @Test void moveToFirstJumpsToTop() {
        var items = List.of(item("a","","1"), item("b","","2"), item("c","","3"));
        var ls = new ListSelect<>(items, 7);
        ls.moveDown(); ls.moveDown();
        ls.moveToFirst();
        assertThat(ls.getSelectedIndex()).isEqualTo(0);
    }

    @Test void moveToLastJumpsToBottom() {
        var items = List.of(item("a","","1"), item("b","","2"), item("c","","3"));
        var ls = new ListSelect<>(items, 7);
        ls.moveToLast();
        assertThat(ls.getSelectedIndex()).isEqualTo(2);
    }

    @Test void setItemsPreservesIndex() {
        var ls = new ListSelect<>(List.of(item("a","","1"), item("b","","2")), 7);
        ls.moveDown(); // index = 1
        ls.setItems(List.of(item("x","","1"), item("y","","2"), item("z","","3")));
        assertThat(ls.getSelectedIndex()).isEqualTo(1);
    }

    @Test void setItemsClampsIndex() {
        var ls = new ListSelect<>(List.of(item("a","","1"), item("b","","2"), item("c","","3")), 7);
        ls.moveDown(); ls.moveDown(); // index = 2
        ls.setItems(List.of(item("x","","1"))); // shrink
        assertThat(ls.getSelectedIndex()).isEqualTo(0);
    }

    @Test void customRowRenderer() {
        var ls = new ListSelect<>(List.of(item("test", "desc", "v")), 7);
        ls.setRowRenderer((item, selected, width) ->
            new AttributedString("CUSTOM:" + item.label()));
        var lines = ls.render(40);
        assertThat(lines.getFirst().toString()).startsWith("CUSTOM:test");
    }
}
