package io.github.samzhu.grimo;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.springframework.shell.jline.PromptProvider;

/**
 * 多行 prompt：分隔線 + ❯ 箭頭，實現緊湊輸入框佈局。
 *
 * 設計說明：
 * - 上方分隔線嵌入 prompt，每次 readLine 自動渲染（寬度跟隨終端）
 * - 下方分隔線由 {@link StatusLineRenderer} 的 {@code setBorder(true)} 自動渲染
 * - 多行 prompt 中，JLine 游標定位在最後一行（❯ 之後），backspace 不會刪到分隔線
 * - 佈局效果：banner → ──── → ❯ input → ──── → status info
 *
 * @see StatusLineRenderer
 * @see <a href="https://github.com/jline/jline3/wiki/Using-line-readers">JLine LineReader</a>
 */
public class GrimoPromptProvider implements PromptProvider {

    private static final AttributedStyle CYAN = AttributedStyle.DEFAULT.foreground(37);
    private static final AttributedStyle GRAY = AttributedStyle.DEFAULT.foreground(245);

    private final Terminal terminal;

    public GrimoPromptProvider(Terminal terminal) {
        this.terminal = terminal;
    }

    @Override
    public AttributedString getPrompt() {
        var sb = new AttributedStringBuilder();
        // 分隔線：terminal 全寬，gray 色
        String separator = "─".repeat(terminal.getWidth());
        sb.styled(GRAY, separator);
        sb.append("\n");
        // 箭頭 prompt：cyan 品牌色
        sb.styled(CYAN, "❯ ");
        return sb.toAttributedString();
    }
}
