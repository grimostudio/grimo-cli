package io.github.samzhu.grimo.tui.view;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentViewTest {

    // --- appendAiReply: \n split ---

    @Test
    void appendAiReply_splitsNewlines_noRenderedLineContainsNewline() {
        var cv = new ContentView();
        cv.appendAiReply("line1\nline2\nline3");
        var rendered = cv.render(80, 10);
        for (var line : rendered) {
            assertThat(line.toString()).doesNotContain("\n");
        }
    }

    @Test
    void appendAiReply_multiLine_firstLineHasBulletPrefix() {
        var cv = new ContentView();
        cv.appendAiReply("hello\nworld");
        var rendered = cv.render(80, 20);
        var nonEmpty = rendered.stream()
                .filter(l -> !l.toString().isBlank())
                .toList();
        assertThat(nonEmpty).hasSizeGreaterThanOrEqualTo(2);
        assertThat(nonEmpty.getFirst().toString()).startsWith("⏺ hello");
        assertThat(nonEmpty.get(1).toString()).startsWith("  world");
    }

    @Test
    void appendAiReply_trailingNewline_preservesEmptyLine() {
        var cv = new ContentView();
        cv.appendAiReply("hello\n");
        var rendered = cv.render(80, 10);
        for (var line : rendered) {
            assertThat(line.toString()).doesNotContain("\n");
        }
    }

    @Test
    void appendAiReply_singleLine_behaviorUnchanged() {
        var cv = new ContentView();
        cv.appendAiReply("just one line");
        var rendered = cv.render(80, 10);
        var nonEmpty = rendered.stream()
                .filter(l -> !l.toString().isBlank())
                .toList();
        assertThat(nonEmpty.getFirst().toString()).startsWith("⏺ just one line");
    }

    // --- appendError: \n split ---

    @Test
    void appendError_splitsNewlines_noRenderedLineContainsNewline() {
        var cv = new ContentView();
        cv.appendError("error line1\nerror line2");
        var rendered = cv.render(80, 10);
        for (var line : rendered) {
            assertThat(line.toString()).doesNotContain("\n");
        }
    }

    @Test
    void appendError_multiLine_firstLineHasWarningPrefix() {
        var cv = new ContentView();
        cv.appendError("bad\nthing");
        var rendered = cv.render(80, 20);
        var nonEmpty = rendered.stream()
                .filter(l -> !l.toString().isBlank())
                .toList();
        assertThat(nonEmpty).hasSizeGreaterThanOrEqualTo(2);
        assertThat(nonEmpty.getFirst().toString()).startsWith("⚠ bad");
        assertThat(nonEmpty.get(1).toString()).startsWith("  thing");
    }

    // --- appendLine: defensive \n check ---

    @Test
    void appendLine_withEmbeddedNewline_splits() {
        var cv = new ContentView();
        var styled = new AttributedString("styled\ntext",
                AttributedStyle.DEFAULT.foreground(245));
        cv.appendLine(styled);
        var rendered = cv.render(80, 10);
        for (var line : rendered) {
            assertThat(line.toString()).doesNotContain("\n");
        }
    }

    @Test
    void appendLine_withoutNewline_addsDirectly() {
        var cv = new ContentView();
        var plain = new AttributedString("no newline here",
                AttributedStyle.DEFAULT.foreground(245));
        cv.appendLine(plain);
        var rendered = cv.render(80, 10);
        var nonEmpty = rendered.stream()
                .filter(l -> !l.toString().isBlank())
                .toList();
        assertThat(nonEmpty).anyMatch(l -> l.toString().contains("no newline here"));
    }

    // --- removeLastLine: wrappedCache sync ---

    @Test
    void removeLastLine_syncsWrappedCache() {
        var cv = new ContentView();
        cv.appendAiReply("first");
        cv.appendLine(new AttributedString("temporary"));
        var beforeRemove = cv.getBufferLines(80);
        int sizeBefore = beforeRemove.size();

        cv.removeLastLine();
        var afterRemove = cv.getBufferLines(80);

        assertThat(afterRemove.size()).isLessThan(sizeBefore);
    }

    @Test
    void removeLastLine_wideLine_removesAllWrappedEntries() {
        var cv = new ContentView();
        cv.appendLine(new AttributedString("A".repeat(50)));
        var before = cv.getBufferLines(20);
        int sizeBefore = before.size();

        cv.removeLastLine();
        var after = cv.getBufferLines(20);

        assertThat(after.size()).isEqualTo(sizeBefore - 3);
    }
}
