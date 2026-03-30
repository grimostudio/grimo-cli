package io.github.samzhu.grimo;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.List;

/**
 * Status 區：顯示 agent/model/workspace/計數資訊（1 行）。
 *
 * 設計說明：
 * - 一般對話：灰色文字
 * - Skill 執行時：tier icon 用對應色彩（lite=green, std=gray, pro=yellow）
 */
public class GrimoStatusView {

    private static final AttributedStyle STATUS_STYLE = AttributedStyle.DEFAULT.foreground(245);
    private static final AttributedStyle LITE_STYLE = AttributedStyle.DEFAULT.foreground(2);   // green
    private static final AttributedStyle PRO_STYLE = AttributedStyle.DEFAULT.foreground(3);    // yellow

    private String statusText;
    private String tierIcon;   // null = 不顯示 tier icon
    private String tierStyle;  // "lite" / "std" / "pro"

    public GrimoStatusView(String statusText) {
        this.statusText = statusText;
    }

    public void setStatusText(String statusText) {
        this.statusText = statusText;
    }

    /**
     * 設定 tier 顯示資訊。傳 null 清除。
     */
    public void setTierDisplay(String icon, String style) {
        this.tierIcon = icon;
        this.tierStyle = style;
    }

    public List<AttributedString> render(int cols) {
        if (tierIcon != null && tierStyle != null) {
            var builder = new AttributedStringBuilder();
            var iconStyle = switch (tierStyle) {
                case "lite" -> LITE_STYLE;
                case "pro" -> PRO_STYLE;
                default -> STATUS_STYLE;
            };
            builder.styled(iconStyle, tierIcon + " ");
            builder.styled(STATUS_STYLE, statusText);
            var full = builder.toAttributedString();
            return List.of(full);
        }

        String text = statusText;
        if (text.length() > cols) {
            text = text.substring(0, cols);
        }
        return List.of(new AttributedString(text, STATUS_STYLE));
    }
}
