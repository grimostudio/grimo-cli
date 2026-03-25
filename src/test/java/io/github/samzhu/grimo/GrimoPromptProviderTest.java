package io.github.samzhu.grimo;

import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrimoPromptProviderTest {

    @Test
    void promptShouldContainSeparatorAndArrow() {
        Terminal mockTerminal = mock(Terminal.class);
        when(mockTerminal.getWidth()).thenReturn(40);
        var provider = new GrimoPromptProvider(mockTerminal);
        String plain = provider.getPrompt().toString();
        assertThat(plain).contains("─"); // 分隔線
        assertThat(plain).contains("❯");  // 箭頭
    }

    @Test
    void separatorWidthShouldMatchTerminalWidth() {
        Terminal mockTerminal = mock(Terminal.class);
        when(mockTerminal.getWidth()).thenReturn(20);
        var provider = new GrimoPromptProvider(mockTerminal);
        String plain = provider.getPrompt().toString();
        // 分隔線 20 個 ─ + \n + ❯ 空格
        String firstLine = plain.split("\n")[0];
        assertThat(firstLine).hasSize(20);
    }
}
