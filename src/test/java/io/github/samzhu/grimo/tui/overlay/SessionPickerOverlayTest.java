package io.github.samzhu.grimo.tui.overlay;

import io.github.samzhu.grimo.shared.session.SessionIndex;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SessionPickerOverlayTest {

    @Test
    void renderShouldShowSessionEntries() {
        var entries = List.of(
            new SessionIndex.Entry("a3f1b2c4",
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600),
                24, "幫我重構 auth module", "main", "claude", "opus"),
            new SessionIndex.Entry("b7e2f190",
                Instant.now().minusSeconds(86400), Instant.now().minusSeconds(43200),
                12, "設計 session", "main", "gemini", "flash")
        );
        var picker = new SessionPickerOverlay(entries);
        var lines = picker.render(60);

        assertThat(lines).isNotEmpty();
        // Should contain session IDs
        var text = lines.stream()
                .map(Object::toString).reduce("", String::concat);
        assertThat(text).contains("a3f1b2c4");
        assertThat(text).contains("b7e2f190");
    }

    @Test
    void renderEmptyShouldShowNoSessionMessage() {
        var picker = new SessionPickerOverlay(List.of());
        var lines = picker.render(60);

        var text = lines.stream()
                .map(Object::toString).reduce("", String::concat);
        assertThat(text).contains("No previous sessions");
    }

    @Test
    void moveDownShouldChangeSelection() {
        var entries = List.of(
            new SessionIndex.Entry("aaa", Instant.now(), Instant.now(), 1, "a", "main", "c", "o"),
            new SessionIndex.Entry("bbb", Instant.now(), Instant.now(), 2, "b", "main", "c", "o")
        );
        var picker = new SessionPickerOverlay(entries);
        assertThat(picker.getSelectedIndex()).isEqualTo(0);
        picker.moveDown();
        assertThat(picker.getSelectedIndex()).isEqualTo(1);
        picker.moveDown(); // should not go past last
        assertThat(picker.getSelectedIndex()).isEqualTo(1);
    }

    @Test
    void getSelectedEntryShouldReturnCorrectEntry() {
        var entry = new SessionIndex.Entry("aaa", Instant.now(), Instant.now(), 1, "a", "main", "c", "o");
        var picker = new SessionPickerOverlay(List.of(entry));
        assertThat(picker.getSelectedEntry().sessionId()).isEqualTo("aaa");
    }
}
