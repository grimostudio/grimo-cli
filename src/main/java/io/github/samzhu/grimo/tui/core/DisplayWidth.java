package io.github.samzhu.grimo.tui.core;

import org.jline.utils.WCWidth;

import java.util.ArrayList;
import java.util.List;

/**
 * Terminal column 寬度感知的字串操作。
 * 封裝 JLine WCWidth，所有 TUI 元件共用。
 *
 * 設計說明：
 * - CJK 字 = 2 columns, ASCII = 1 column, combining marks = 0
 * - 使用 codePointAt 而非 charAt，正確處理 supplementary characters
 * - truncate 不會切半個 CJK 字
 * - 所有方法 null-safe（null → 空字串）
 *
 * @see <a href="https://github.com/jline/jline3/blob/master/terminal/src/main/java/org/jline/utils/WCWidth.java">
 *      JLine WCWidth — Unicode 16.0 East Asian Width</a>
 * @see <a href="https://github.com/charmbracelet/lipgloss">Lipgloss — Go TUI styling with wcwidth</a>
 */
public final class DisplayWidth {

    private DisplayWidth() {}

    /**
     * 字串的 terminal display column 寬度。
     * CJK = 2, ASCII = 1, combining = 0, control = 0。
     */
    public static int of(String s) {
        if (s == null || s.isEmpty()) return 0;
        int width = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int w = WCWidth.wcwidth(cp);
            width += Math.max(w, 0);
            i += Character.charCount(cp);
        }
        return width;
    }

    /**
     * 右補空白到指定欄寬（左對齊）。
     * 超過 columns 時截斷。
     */
    public static String padRight(String s, int columns) {
        if (s == null) s = "";
        int width = of(s);
        if (width > columns) return truncate(s, columns);
        if (width == columns) return s;
        return s + " ".repeat(columns - width);
    }

    /**
     * 左補空白到指定欄寬（右對齊）。
     */
    public static String padLeft(String s, int columns) {
        if (s == null) s = "";
        int width = of(s);
        if (width > columns) return truncate(s, columns);
        if (width == columns) return s;
        return " ".repeat(columns - width) + s;
    }

    /**
     * 置中（左右均分空白，奇數時右側多一格）。
     */
    public static String center(String s, int columns) {
        if (s == null) s = "";
        int width = of(s);
        if (width >= columns) return truncate(s, columns);
        int totalPad = columns - width;
        int left = totalPad / 2;
        int right = totalPad - left;
        return " ".repeat(left) + s + " ".repeat(right);
    }

    /**
     * 截斷到 maxColumns，超過加 "…"。
     * 保證不切半個 CJK 字。
     */
    public static String truncate(String s, int maxColumns) {
        if (s == null) return "";
        if (of(s) <= maxColumns) return s;
        if (maxColumns <= 0) return "";
        if (maxColumns == 1) return "…";

        int target = maxColumns - 1;
        var sb = new StringBuilder();
        int width = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int w = WCWidth.wcwidth(cp);
            if (w < 0) w = 0;
            if (width + w > target) break;
            sb.appendCodePoint(cp);
            width += w;
            i += Character.charCount(cp);
        }
        sb.append("…");
        return sb.toString();
    }

    /**
     * 產生指定欄寬的空白。
     */
    public static String fill(int columns) {
        return columns > 0 ? " ".repeat(columns) : "";
    }

    /**
     * 依 column 寬度 wrap。
     * CJK 字之間允許斷行；空白為優先斷點。
     */
    public static List<String> wrap(String s, int maxColumns) {
        if (s == null || s.isEmpty()) return List.of("");
        if (maxColumns <= 0) return List.of(s);

        var lines = new ArrayList<String>();
        var current = new StringBuilder();
        int currentWidth = 0;

        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int w = WCWidth.wcwidth(cp);
            if (w < 0) w = 0;

            if (currentWidth + w > maxColumns && currentWidth > 0) {
                lines.add(current.toString());
                current = new StringBuilder();
                currentWidth = 0;
            }

            current.appendCodePoint(cp);
            currentWidth += w;
            i += Character.charCount(cp);
        }

        if (current.length() > 0) {
            lines.add(current.toString());
        }

        return lines;
    }
}
