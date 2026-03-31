package io.github.samzhu.grimo.shared.tui;

import java.util.Optional;

/**
 * 正規化的選取範圍（start ≤ end）。
 *
 * 設計說明：
 * - 無論使用者從上往下或從下往上拖曳，startRow ≤ endRow 恆成立
 * - colsForRow() 回傳某一行在選取範圍內的列範圍，用於逐行渲染 highlight
 * - 參考 WezTerm SelectionRange.cols_for_row() 設計
 *
 * @param startRow 選取起始行（buffer-absolute）
 * @param startCol 起始列（column-based，CJK 字元佔 2 列）
 * @param endRow   選取結束行（buffer-absolute）
 * @param endCol   結束列
 *
 * @see <a href="https://github.com/wez/wezterm/blob/main/wezterm-gui/src/selection.rs">WezTerm selection.rs</a>
 */
public record SelectionRange(int startRow, int startCol, int endRow, int endCol) {

    /**
     * 某行在選取範圍內的列範圍。
     *
     * @param row       要查詢的行號（buffer-absolute）
     * @param lineWidth 該行的 column 寬度（用於「到行尾」的計算）
     * @return 列範圍，empty 代表該行不在選取範圍內
     */
    public Optional<ColSpan> colsForRow(int row, int lineWidth) {
        if (row < startRow || row > endRow || lineWidth <= 0) {
            return Optional.empty();
        }
        int s, e;
        if (startRow == endRow) {
            // 單行選取
            s = startCol;
            e = endCol;
        } else if (row == startRow) {
            // 首行：startCol → 行尾
            s = startCol;
            e = lineWidth;
        } else if (row == endRow) {
            // 末行：行首 → endCol
            s = 0;
            e = endCol;
        } else {
            // 中間行：全行
            s = 0;
            e = lineWidth;
        }
        // clamp
        s = Math.max(0, Math.min(s, lineWidth));
        e = Math.max(s, Math.min(e, lineWidth));
        if (s == e) return Optional.empty();
        return Optional.of(new ColSpan(s, e));
    }

    /** 列範圍（start inclusive, end exclusive） */
    public record ColSpan(int start, int end) {}
}
