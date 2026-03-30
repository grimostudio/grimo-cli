package io.github.samzhu.grimo;

import io.github.samzhu.grimo.shared.tui.DisplayWidth;
import io.github.samzhu.grimo.shared.tui.TuiStatusBar;
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
            var iconStyle = switch (tierStyle) {
                case "lite" -> LITE_STYLE;
                case "pro" -> PRO_STYLE;
                default -> STATUS_STYLE;
            };
            // 組合 icon + 空格 + statusText，再以 column-aware 方式截斷/補齊
            String combined = tierIcon + " " + statusText;
            String fitted = DisplayWidth.padRight(DisplayWidth.truncate(combined, cols), cols);
            // icon 部分用 tierStyle 色彩，其餘用 STATUS_STYLE
            int iconPartLen = (tierIcon + " ").length();
            var builder = new AttributedStringBuilder();
            // 若截斷後字串短於 icon 部分，只畫截斷後的部分
            if (fitted.length() <= iconPartLen) {
                builder.styled(iconStyle, fitted);
            } else {
                builder.styled(iconStyle, fitted.substring(0, iconPartLen));
                builder.styled(STATUS_STYLE, fitted.substring(iconPartLen));
            }
            return List.of(builder.toAttributedString());
        }

        // 使用 DisplayWidth 做 column-aware 截斷 + 補齊，修正 CJK 字元被錯誤截斷的問題
        return List.of(TuiStatusBar.of(statusText, STATUS_STYLE, cols));
    }
}
