package io.github.samzhu.grimo.tui.selection;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SelectionRangeTest {

    @Test
    void singleLineSelection() {
        var range = new SelectionRange(3, 5, 3, 10);
        var span = range.colsForRow(3, 80);
        assertThat(span).isPresent();
        assertThat(span.get().start()).isEqualTo(5);
        assertThat(span.get().end()).isEqualTo(10);
    }

    @Test
    void multiLineFirstRow() {
        var range = new SelectionRange(2, 10, 5, 20);
        var span = range.colsForRow(2, 80);
        assertThat(span).isPresent();
        assertThat(span.get().start()).isEqualTo(10);
        assertThat(span.get().end()).isEqualTo(80);
    }

    @Test
    void multiLineMiddleRow() {
        var range = new SelectionRange(2, 10, 5, 20);
        var span = range.colsForRow(3, 80);
        assertThat(span).isPresent();
        assertThat(span.get().start()).isEqualTo(0);
        assertThat(span.get().end()).isEqualTo(80);
    }

    @Test
    void multiLineLastRow() {
        var range = new SelectionRange(2, 10, 5, 20);
        var span = range.colsForRow(5, 80);
        assertThat(span).isPresent();
        assertThat(span.get().start()).isEqualTo(0);
        assertThat(span.get().end()).isEqualTo(20);
    }

    @Test
    void rowOutsideRange() {
        var range = new SelectionRange(2, 10, 5, 20);
        assertThat(range.colsForRow(0, 80)).isEmpty();
        assertThat(range.colsForRow(6, 80)).isEmpty();
    }

    @Test
    void zeroWidthLineReturnsEmpty() {
        var range = new SelectionRange(2, 10, 5, 20);
        assertThat(range.colsForRow(3, 0)).isEmpty();
    }
}
