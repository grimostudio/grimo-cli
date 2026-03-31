package io.github.samzhu.grimo.shared.tui;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class ClipboardWriterTest {

    @Test
    void osc52FormatUsesBase64AndBel() {
        String text = "Hello World";
        String expected = Base64.getEncoder().encodeToString(text.getBytes(UTF_8));
        String seq = ClipboardWriter.buildOsc52Sequence(text, false, false);
        assertThat(seq).isEqualTo("\033]52;c;" + expected + "\007");
    }

    @Test
    void osc52Base64HasNoLineBreaks() {
        String text = "a".repeat(200);
        String seq = ClipboardWriter.buildOsc52Sequence(text, false, false);
        assertThat(seq).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void osc52TmuxWrapsDcs() {
        String text = "test";
        String seq = ClipboardWriter.buildOsc52Sequence(text, true, false);
        assertThat(seq).startsWith("\033Ptmux;\033");
        assertThat(seq).endsWith("\033\\");
        String b64 = Base64.getEncoder().encodeToString(text.getBytes(UTF_8));
        assertThat(seq).contains("\033]52;c;" + b64 + "\007");
    }

    @Test
    void osc52ScreenWrapsDcs() {
        String text = "test";
        String seq = ClipboardWriter.buildOsc52Sequence(text, false, true);
        assertThat(seq).startsWith("\033P");
        assertThat(seq).endsWith("\033\\");
    }

    @Test
    void isWithinOsc52LimitAccepts99KB() {
        String small = "x".repeat(99_000);
        assertThat(ClipboardWriter.isWithinOsc52Limit(small)).isTrue();
    }

    @Test
    void isWithinOsc52LimitRejects101KB() {
        String large = "x".repeat(101_000);
        assertThat(ClipboardWriter.isWithinOsc52Limit(large)).isFalse();
    }
}
