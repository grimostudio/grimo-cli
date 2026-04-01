package io.github.samzhu.grimo.tui.selection;

import io.github.samzhu.grimo.tui.screen.BufferLine;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class TextSelectionTest {

    private List<BufferLine> buffer(String... texts) {
        return java.util.Arrays.stream(texts)
                .map(t -> BufferLine.of(new AttributedString(t)))
                .toList();
    }

    @Test
    void startAndFinishSingleLine() {
        var sel = new TextSelection();
        sel.startAt(0, 3);
        sel.dragTo(0, 8);
        var text = sel.finish(buffer("Hello, World!"));
        assertThat(text).isEqualTo("lo, W");
    }

    @Test
    void multiLineSelection() {
        var sel = new TextSelection();
        sel.startAt(0, 7);
        sel.dragTo(1, 5);
        var text = sel.finish(buffer("Hello, World!", "Goodbye Moon!"));
        assertThat(text).isEqualTo("World!\nGoodb");
    }

    @Test
    void reverseDragNormalizesRange() {
        var sel = new TextSelection();
        sel.startAt(1, 5);
        sel.dragTo(0, 3);
        var text = sel.finish(buffer("Hello, World!", "Goodbye Moon!"));
        assertThat(text).isEqualTo("lo, World!\nGoodb");
    }

    @Test
    void wrappedLinesDoNotInsertNewline() {
        var lines = List.of(
                BufferLine.of(new AttributedString("Hello ")),
                BufferLine.wrapped(new AttributedString("World!")),
                BufferLine.of(new AttributedString("Second line"))
        );
        var sel = new TextSelection();
        sel.startAt(0, 0);
        sel.dragTo(2, 6);
        var text = sel.finish(lines);
        assertThat(text).isEqualTo("Hello World!\nSecond");
    }

    @Test
    void unselectableLinesAreSkipped() {
        var lines = List.of(
                BufferLine.of(new AttributedString("Line 1")),
                BufferLine.unselectable(new AttributedString("──────")),
                BufferLine.of(new AttributedString("Line 3"))
        );
        var sel = new TextSelection();
        sel.startAt(0, 0);
        sel.dragTo(2, 6);
        var text = sel.finish(lines);
        assertThat(text).isEqualTo("Line 1\nLine 3");
    }

    @Test
    void finishClearsState() {
        var sel = new TextSelection();
        sel.startAt(0, 0);
        sel.dragTo(0, 5);
        sel.finish(buffer("Hello"));
        assertThat(sel.isActive()).isFalse();
        assertThat(sel.getRange()).isNull();
    }

    @Test
    void cancelClearsState() {
        var sel = new TextSelection();
        sel.startAt(0, 0);
        sel.dragTo(0, 5);
        sel.cancel();
        assertThat(sel.isActive()).isFalse();
        assertThat(sel.getRange()).isNull();
    }

    @Test
    void clickWithoutDragReturnsEmpty() {
        var sel = new TextSelection();
        sel.startAt(3, 10);
        var text = sel.finish(buffer("Line0", "Line1", "Line2", "Line3 text here"));
        assertThat(text).isEmpty();
    }

    @Test
    void cjkColumnSubSequence() {
        var sel = new TextSelection();
        sel.startAt(0, 2);
        sel.dragTo(0, 7);
        var text = sel.finish(List.of(
                BufferLine.of(new AttributedString("你好World"))));
        assertThat(text).isEqualTo("好Wor");
    }

    @Test
    void getRangeReturnsNullWhenInactive() {
        var sel = new TextSelection();
        assertThat(sel.getRange()).isNull();
    }

    @Test
    void getRangeReturnsSameForBothDragDirections() {
        var sel = new TextSelection();
        sel.startAt(5, 10);
        sel.dragTo(2, 3);
        var range = sel.getRange();
        assertThat(range).isNotNull();
        assertThat(range.startRow()).isEqualTo(2);
        assertThat(range.startCol()).isEqualTo(3);
        assertThat(range.endRow()).isEqualTo(5);
        assertThat(range.endCol()).isEqualTo(10);
    }
}
