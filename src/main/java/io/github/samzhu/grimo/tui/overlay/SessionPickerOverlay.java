package io.github.samzhu.grimo.tui.overlay;

import io.github.samzhu.grimo.shared.session.SessionIndex;
import io.github.samzhu.grimo.tui.core.DisplayWidth;
import io.github.samzhu.grimo.tui.core.Renderable;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Session 選擇 overlay：顯示歷史 session 列表供使用者選取 resume。
 *
 * 設計說明：
 * - 跟隨 McpPanel/SlashMenu pattern（純資料 + render()）
 * - 不負責按鍵處理（由 TuiKeyHandler 處理）
 * - 每筆 entry 兩行：(1) ID 時間 訊息數 branch agent/model (2) firstUserMessage
 */
public class SessionPickerOverlay implements Renderable {

    /** 品牌標誌色 steel blue（ANSI 256 色碼 67），與 McpPanel/SlashMenu 一致 */
    private static final int BRAND_COLOR = 67;
    private static final AttributedStyle BRAND_STYLE = AttributedStyle.DEFAULT.foreground(BRAND_COLOR);
    private static final AttributedStyle DIM_STYLE = AttributedStyle.DEFAULT.foreground(245);
    private static final AttributedStyle SELECTED_STYLE = AttributedStyle.DEFAULT.foreground(BRAND_COLOR).bold();
    private static final AttributedStyle HINT_STYLE = AttributedStyle.DEFAULT.foreground(245);

    private final List<SessionIndex.Entry> entries;
    private int selectedIndex = 0;

    public SessionPickerOverlay(List<SessionIndex.Entry> entries) {
        this.entries = entries;
    }

    public void moveUp() {
        if (selectedIndex > 0) selectedIndex--;
    }

    public void moveDown() {
        if (selectedIndex < entries.size() - 1) selectedIndex++;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public SessionIndex.Entry getSelectedEntry() {
        if (entries.isEmpty()) return null;
        return entries.get(selectedIndex);
    }

    @Override
    public List<AttributedString> render(int cols) {
        var lines = new ArrayList<AttributedString>();

        // Title
        lines.add(new AttributedString(
                DisplayWidth.padRight("Session Resume", cols), BRAND_STYLE));
        lines.add(new AttributedString(DisplayWidth.padRight("", cols)));

        if (entries.isEmpty()) {
            lines.add(new AttributedString(
                    DisplayWidth.padRight("  No previous sessions found.", cols), DIM_STYLE));
            lines.add(new AttributedString(DisplayWidth.padRight("", cols)));
            lines.add(new AttributedString(
                    DisplayWidth.padRight("  Esc close", cols), HINT_STYLE));
            return lines;
        }

        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            boolean selected = i == selectedIndex;
            var style = selected ? SELECTED_STYLE : DIM_STYLE;
            String marker = selected ? "> " : "  ";

            // Line 1: ID  time  msgs  branch  agent/model
            String timeAgo = formatTimeAgo(entry.lastActiveAt());
            String line1 = String.format("%s%s  %s  %d msgs  %s  %s/%s",
                    marker, entry.sessionId(), timeAgo, entry.messageCount(),
                    entry.gitBranch() != null ? entry.gitBranch() : "",
                    entry.agent() != null ? entry.agent() : "",
                    entry.model() != null ? entry.model() : "");
            lines.add(new AttributedString(
                    DisplayWidth.padRight(DisplayWidth.truncate(line1, cols), cols), style));

            // Line 2: firstUserMessage
            String msg = entry.firstUserMessage() != null ? entry.firstUserMessage() : "";
            String line2 = "    " + msg;
            lines.add(new AttributedString(
                    DisplayWidth.padRight(DisplayWidth.truncate(line2, cols), cols),
                    selected ? SELECTED_STYLE : DIM_STYLE));

            // Blank separator between entries
            if (i < entries.size() - 1) {
                lines.add(new AttributedString(DisplayWidth.padRight("", cols)));
            }
        }

        lines.add(new AttributedString(DisplayWidth.padRight("", cols)));
        lines.add(new AttributedString(
                DisplayWidth.padRight("  ↑↓ navigate  Enter select  Esc cancel", cols), HINT_STYLE));

        return lines;
    }

    private String formatTimeAgo(Instant time) {
        if (time == null) return "";
        Duration d = Duration.between(time, Instant.now());
        if (d.toDays() > 0) return d.toDays() + "d ago";
        if (d.toHours() > 0) return d.toHours() + "h ago";
        if (d.toMinutes() > 0) return d.toMinutes() + "m ago";
        return "now";
    }
}
