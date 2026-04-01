package io.github.samzhu.grimo.tui.widget;

import io.github.samzhu.grimo.tui.core.DisplayWidth;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * 可捲動選擇器渲染。
 * 每行保證 columnLength == width。
 *
 * 設計說明：
 * - 靜態方法，不持有狀態（狀態由 SlashMenuView 管理）
 * - selected item 用 BRAND_COLOR 高亮
 * - 借鑑 OpenCode InlineTool 的單行緊湊風格
 *
 * @see DisplayWidth — 寬度感知字串操作
 */
public final class Selector {

    private static final int BRAND_COLOR = 67;

    private Selector() {}

    public static List<AttributedString> render(
            List<String> items, int selectedIndex, int maxVisible, int width) {
        if (items.isEmpty()) return List.of();

        int visible = Math.min(items.size(), maxVisible);
        var lines = new ArrayList<AttributedString>(visible);

        for (int i = 0; i < visible; i++) {
            boolean active = (i == selectedIndex);
            String item = items.get(i);
            String prefix = active ? "> " : "  ";
            String text = DisplayWidth.padRight(prefix + item, width);

            var sb = new AttributedStringBuilder();
            if (active) {
                sb.styled(AttributedStyle.DEFAULT.foreground(BRAND_COLOR), text);
            } else {
                sb.append(text);
            }
            lines.add(sb.toAttributedString());
        }
        return lines;
    }
}
