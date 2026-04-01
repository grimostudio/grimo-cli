package io.github.samzhu.grimo.tui.selection;

import io.github.samzhu.grimo.tui.screen.BufferLine;
import org.jline.utils.AttributedString;
import java.util.List;

/**
 * 文字選取模型：管理 anchor/cursor 座標與文字擷取。
 *
 * 設計說明：
 * - 座標系統用 buffer-absolute（不受 viewport 滾動影響）
 *   參考 tmux sely/endsely 和 WezTerm StableRowIndex
 * - 模型只管座標和狀態，不碰渲染（渲染由 applyHighlight 在 Screen 層處理）
 * - Thread safety：所有欄位用 synchronized(this) 保護，
 *   因為 input thread（mouse events）和 render thread 同時存取
 *
 * @see <a href="https://github.com/tmux/tmux/blob/master/window-copy.c">tmux window-copy.c</a>
 * @see <a href="https://github.com/wez/wezterm/blob/main/wezterm-gui/src/selection.rs">WezTerm selection.rs</a>
 */
public class TextSelection {

    private int anchorRow, anchorCol;
    private int cursorRow, cursorCol;
    private boolean active;

    public synchronized void startAt(int row, int col) {
        this.anchorRow = row;
        this.anchorCol = col;
        this.cursorRow = row;
        this.cursorCol = col;
        this.active = true;
    }

    public synchronized void dragTo(int row, int col) {
        if (!active) return;
        this.cursorRow = row;
        this.cursorCol = col;
    }

    /**
     * 結束選取：擷取文字並清除狀態。
     *
     * 設計說明：
     * - wrapped 行之間不加 \n（參考 tmux GRID_LINE_WRAPPED）
     * - 不可選行（separator）直接跳過
     * - 最後一行不加尾部 \n
     * - columnSubSequence 已處理 CJK 雙寬字元邊界
     */
    public synchronized String finish(List<BufferLine> buffer) {
        if (!active) return "";
        var range = computeRange();
        active = false;
        if (range == null) return "";
        return extractText(buffer, range);
    }

    public synchronized void cancel() {
        active = false;
    }

    public synchronized SelectionRange getRange() {
        if (!active) return null;
        return computeRange();
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized int getCursorRow() {
        return cursorRow;
    }

    public synchronized int getCursorCol() {
        return cursorCol;
    }

    private SelectionRange computeRange() {
        if (anchorRow == cursorRow && anchorCol == cursorCol) return null;
        int sr, sc, er, ec;
        if (anchorRow < cursorRow || (anchorRow == cursorRow && anchorCol < cursorCol)) {
            sr = anchorRow; sc = anchorCol; er = cursorRow; ec = cursorCol;
        } else {
            sr = cursorRow; sc = cursorCol; er = anchorRow; ec = anchorCol;
        }
        return new SelectionRange(sr, sc, er, ec);
    }

    private String extractText(List<BufferLine> buffer, SelectionRange range) {
        var sb = new StringBuilder();
        for (int row = range.startRow(); row <= range.endRow() && row < buffer.size(); row++) {
            var line = buffer.get(row);
            if (!line.selectable()) continue;

            var span = range.colsForRow(row, line.text().columnLength());
            if (span.isEmpty()) continue;

            var sub = line.text().columnSubSequence(span.get().start(), span.get().end());
            sb.append(sub.toString());

            if (row < range.endRow()) {
                boolean nextWrapped = (row + 1 < buffer.size()) && buffer.get(row + 1).wrapped();
                if (!nextWrapped) {
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }
}
