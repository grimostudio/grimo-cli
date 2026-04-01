package io.github.samzhu.grimo.tui.screen;

import org.jline.utils.AttributedString;

/**
 * 螢幕 buffer 中的一行，附帶選取相關 metadata。
 *
 * 設計說明：
 * - wrapped = true → 這行是上一行 columnSplitLength() 切分出的延續行，
 *   文字擷取時不加 \n（參考 tmux GRID_LINE_WRAPPED flag）
 * - selectable = false → separator 行等不可選取的裝飾行
 *
 * @see <a href="https://github.com/tmux/tmux/blob/master/grid.c">tmux grid.c — GRID_LINE_WRAPPED</a>
 */
public record BufferLine(AttributedString text, boolean wrapped, boolean selectable) {

    /** 建立可選取的非 wrap 行（最常見情境） */
    public static BufferLine of(AttributedString text) {
        return new BufferLine(text, false, true);
    }

    /** 建立可選取的 wrap 延續行 */
    public static BufferLine wrapped(AttributedString text) {
        return new BufferLine(text, true, true);
    }

    /** 建立不可選取的裝飾行（separator 等） */
    public static BufferLine unselectable(AttributedString text) {
        return new BufferLine(text, false, false);
    }
}
