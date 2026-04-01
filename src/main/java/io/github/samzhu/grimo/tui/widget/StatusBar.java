package io.github.samzhu.grimo.tui.widget;

import io.github.samzhu.grimo.tui.core.DisplayWidth;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

/**
 * 單行狀態列，寬度精確 == width。
 * 超過截斷（column-aware），不足補空白。
 *
 * @see DisplayWidth — 寬度計算
 */
public final class StatusBar {

    private StatusBar() {}

    /**
     * 產生一行 AttributedString，display width 精確 == width。
     */
    public static AttributedString of(String text, AttributedStyle style, int width) {
        String fitted = DisplayWidth.padRight(text, width);
        return new AttributedString(fitted, style);
    }
}
