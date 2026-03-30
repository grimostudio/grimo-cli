package io.github.samzhu.grimo.shared.tui;

import java.util.ArrayList;
import java.util.List;

/**
 * 寬度感知的表格 Builder。
 * 保證每行輸出的 display width 精確等於指定 width。
 *
 * 設計說明：
 * - column width=0 表示 Fill（填滿剩餘空間），使用 Layout.horizontal 計算
 * - 每個 cell 用 DisplayWidth.padRight 對齊，超長用 truncate
 * - 多個 Fill column 均分剩餘空間
 *
 * @see Layout — 欄寬計算
 * @see DisplayWidth — 寬度感知字串操作
 */
public final class TuiTable {

    private TuiTable() {}

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<ColDef> columns = new ArrayList<>();
        private final List<String[]> rows = new ArrayList<>();

        public Builder column(String name, int width) {
            columns.add(new ColDef(name, width));
            return this;
        }

        public Builder row(String... values) {
            rows.add(values);
            return this;
        }

        public String build(int totalWidth) {
            int colGap = 1;
            Layout.Slot[] slots = columns.stream()
                    .map(c -> c.width > 0
                            ? (Layout.Slot) new Layout.Fixed(c.width)
                            : new Layout.Fill())
                    .toArray(Layout.Slot[]::new);
            int[] colWidths = Layout.horizontal(totalWidth, colGap, slots);

            var sb = new StringBuilder();
            for (int r = 0; r < rows.size(); r++) {
                String[] row = rows.get(r);
                var line = new StringBuilder();
                for (int c = 0; c < colWidths.length; c++) {
                    if (c > 0) line.append(" ");
                    String value = c < row.length ? row[c] : "";
                    line.append(DisplayWidth.padRight(value, colWidths[c]));
                }
                String lineStr = line.toString();
                int lineWidth = DisplayWidth.of(lineStr);
                if (lineWidth < totalWidth) {
                    lineStr = lineStr + DisplayWidth.fill(totalWidth - lineWidth);
                } else if (lineWidth > totalWidth) {
                    lineStr = DisplayWidth.truncate(lineStr, totalWidth);
                }
                sb.append(lineStr);
                if (r < rows.size() - 1) sb.append("\n");
            }
            return sb.toString();
        }

        private record ColDef(String name, int width) {}
    }
}
