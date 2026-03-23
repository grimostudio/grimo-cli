package io.github.samzhu.grimo;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.springframework.shell.jline.PromptProvider;

/**
 * 極簡 Shell 提示符：只顯示 ❯ 箭頭符號。
 * 所有狀態資訊（agent、model、workspace）由 StatusLineRenderer 在終端底部顯示。
 *
 * @see StatusLineRenderer
 */
public class GrimoPromptProvider implements PromptProvider {

    private static final AttributedStyle CYAN = AttributedStyle.DEFAULT.foreground(37);

    @Override
    public AttributedString getPrompt() {
        return new AttributedString("❯ ", CYAN);
    }
}
