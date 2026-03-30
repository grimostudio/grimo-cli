package io.github.samzhu.grimo.shared.tui;

import org.jline.utils.AttributedString;
import java.util.List;

/**
 * TUI 元件契約。
 * 保證：回傳的每行 columnLength == width。
 * 行數 = 自然高度（不限），由容器決定顯示多少。
 *
 * 設計說明：
 * - 借鑑 Ratatui Widget trait 的 render(area) 模式
 * - 借鑑 OpenCode scrollbox 容器模式：元件不管高度，容器負責捲動
 * - 借鑑 Lipgloss 的 string-in/string-out 簡潔設計
 *
 * @see <a href="https://github.com/anomalyco/opencode">OpenCode — scrollbox 容器模式</a>
 * @see <a href="https://github.com/ratatui/ratatui">Ratatui — Widget trait</a>
 */
public interface TuiComponent {
    List<AttributedString> render(int width);
}
