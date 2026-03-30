package io.github.samzhu.grimo.shared.tui;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * 對話訊息格式化。
 * inline: 單行系統訊息（Skill loaded, Worktree created）
 * block: 多行內容（agent 回覆、使用者輸入、錯誤）
 *
 * 設計說明：
 * - 每行保證 columnLength == width
 * - inline 產生 2 行：title + detail（對齊 Claude Code 風格）
 * - block 自動 wrap，第一行有 role icon，後續行用空白對齊
 * - 借鑑 OpenCode 的 InlineTool / BlockTool 兩種模式
 *
 * @see DisplayWidth — 寬度感知字串操作
 * @see <a href="https://github.com/anomalyco/opencode">OpenCode — InlineTool/BlockTool</a>
 */
public final class TuiMessage {

    private TuiMessage() {}

    public enum Role {
        USER("› ", 7),
        AGENT("● ", 2),
        ERROR("⚠ ", 1),
        SYSTEM("● ", 245);

        final String icon;
        final int color;

        Role(String icon, int color) {
            this.icon = icon;
            this.color = color;
        }
    }

    /**
     * 單行系統訊息：兩行輸出。
     * Line 1: icon + title
     * Line 2: "  └ " + detail
     */
    public static List<AttributedString> inline(
            String icon, String title, String detail, int width) {
        var lines = new ArrayList<AttributedString>(2);

        // Line 1: icon + title
        int iconWidth = DisplayWidth.of(icon);
        String titlePart = DisplayWidth.truncate(title, width - iconWidth);
        int remaining1 = width - iconWidth - DisplayWidth.of(titlePart);

        var line1 = new AttributedStringBuilder();
        line1.styled(AttributedStyle.DEFAULT.foreground(2), icon);
        line1.append(titlePart);
        if (remaining1 > 0) line1.append(DisplayWidth.fill(remaining1));
        lines.add(line1.toAttributedString());

        // Line 2: "  └ " + detail
        String prefix = "  └ ";
        int prefixWidth = DisplayWidth.of(prefix);
        String detailPart = DisplayWidth.truncate(detail, width - prefixWidth);
        int remaining2 = width - prefixWidth - DisplayWidth.of(detailPart);

        var line2 = new AttributedStringBuilder();
        line2.styled(AttributedStyle.DEFAULT.foreground(245), prefix + detailPart);
        if (remaining2 > 0) line2.append(DisplayWidth.fill(remaining2));
        lines.add(line2.toAttributedString());

        return lines;
    }

    /**
     * 多行訊息：自動 wrap，每行保證寬度。
     * 第一行有 role icon，後續行用空白對齊。
     */
    public static List<AttributedString> block(
            Role role, String content, int width) {
        int iconWidth = DisplayWidth.of(role.icon);

        if (content == null || content.isBlank()) {
            var sb = new AttributedStringBuilder();
            sb.styled(AttributedStyle.DEFAULT.foreground(role.color), role.icon);
            int remaining = width - iconWidth;
            if (remaining > 0) sb.append(DisplayWidth.fill(remaining));
            return List.of(sb.toAttributedString());
        }

        int contentWidth = Math.max(1, width - iconWidth);
        var wrappedLines = DisplayWidth.wrap(content, contentWidth);
        var lines = new ArrayList<AttributedString>(wrappedLines.size());

        for (int i = 0; i < wrappedLines.size(); i++) {
            var sb = new AttributedStringBuilder();

            if (i == 0) {
                sb.styled(AttributedStyle.DEFAULT.foreground(role.color), role.icon);
            } else {
                sb.append(DisplayWidth.fill(iconWidth));
            }

            String padded = DisplayWidth.padRight(wrappedLines.get(i), contentWidth);
            sb.append(padded);
            lines.add(sb.toAttributedString());
        }

        return lines;
    }
}
