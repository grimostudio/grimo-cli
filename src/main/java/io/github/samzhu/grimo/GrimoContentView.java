package io.github.samzhu.grimo;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.springframework.shell.jline.tui.component.view.control.BoxView;
import org.springframework.shell.jline.tui.component.view.screen.Screen;
import org.springframework.shell.jline.tui.geom.Position;
import org.springframework.shell.jline.tui.geom.Rectangle;

import java.util.ArrayList;
import java.util.List;

/**
 * Content 區：顯示 banner + 對話紀錄，支援底部對齊渲染和滾動。
 *
 * 設計說明：
 * - 維護 List<AttributedString> lines（所有內容行）
 * - 底部對齊渲染：內容少於 view 高度時，上方留空，內容靠底部顯示
 * - 內容超過 view 高度時，根據 scrollOffset 決定顯示範圍
 * - autoFollow 模式下新內容自動滾到底部
 * - 使用 setDrawFunction() 注入自訂繪製邏輯（BoxView.draw() 是 final）
 *
 * @see <a href="https://docs.spring.io/spring-shell/reference/tui/views/box.html">BoxView :: Spring Shell</a>
 */
public class GrimoContentView extends BoxView {

    /** 品牌標誌色 steel blue（ANSI 256 色碼 67, #5F87AF） */
    private static final int BRAND_COLOR = 67;
    private static final AttributedStyle DARK_BG = AttributedStyle.DEFAULT.background(236);
    private static final AttributedStyle DEFAULT_STYLE = AttributedStyle.DEFAULT;
    private static final AttributedStyle BRAND_STYLE = AttributedStyle.DEFAULT.foreground(BRAND_COLOR);

    private final List<AttributedString> lines = new ArrayList<>();
    private int scrollOffset = 0;
    private boolean autoFollow = true;

    public GrimoContentView() {
        setDrawFunction(this::drawContent);
    }

    /**
     * 初始化 banner 文字（純 ANSI 字串，逐行加入 lines）。
     */
    public void setBannerText(String bannerText) {
        for (String line : bannerText.split("\n")) {
            // fromAnsi() 正確解析 ANSI escape codes 為 style 資訊，
            // 而非 new AttributedString() 把 escape codes 當可見字元（導致寬度爆炸）
            lines.add(AttributedString.fromAnsi(line));
        }
    }

    /**
     * 附加使用者輸入：❯ 前綴 + 深色背景行。
     */
    public void appendUserInput(String text) {
        var sb = new AttributedStringBuilder();
        sb.styled(BRAND_STYLE, "❯ ");
        sb.styled(DARK_BG, text);
        lines.add(sb.toAttributedString());
        lines.add(AttributedString.EMPTY);
        scrollToBottomIfAutoFollow();
    }

    /**
     * 附加 AI 回覆：⏺ 前綴 + 一般文字。
     */
    public void appendAiReply(String text) {
        var sb = new AttributedStringBuilder();
        sb.styled(BRAND_STYLE, "⏺ ");
        sb.append(text);
        lines.add(sb.toAttributedString());
        lines.add(AttributedString.EMPTY);
        scrollToBottomIfAutoFollow();
    }

    /**
     * 附加命令輸出：直接顯示（無前綴），每行加 2 格縮排。
     */
    public void appendCommandOutput(String text) {
        for (String line : text.split("\n")) {
            lines.add(new AttributedString("  " + line));
        }
        lines.add(AttributedString.EMPTY);
        scrollToBottomIfAutoFollow();
    }

    /**
     * 附加錯誤訊息：⚠ 前綴 + 紅色文字。
     */
    public void appendError(String text) {
        var sb = new AttributedStringBuilder();
        sb.styled(AttributedStyle.DEFAULT.foreground(196), "⚠ " + text);
        lines.add(sb.toAttributedString());
        lines.add(AttributedString.EMPTY);
        scrollToBottomIfAutoFollow();
    }

    /**
     * 附加一行原始 AttributedString（用於 streaming 等特殊場景）。
     */
    public void appendLine(AttributedString line) {
        lines.add(line);
        scrollToBottomIfAutoFollow();
    }

    /**
     * 清空所有內容。
     */
    public void clear() {
        lines.clear();
        scrollOffset = 0;
        autoFollow = true;
    }

    // === 滾動控制 ===

    public void scrollUp(int count) {
        scrollOffset = Math.max(0, scrollOffset - count);
        autoFollow = false;
    }

    public void scrollDown(int count) {
        scrollOffset = Math.min(maxOffset(), scrollOffset + count);
        if (scrollOffset >= maxOffset()) {
            autoFollow = true;
        }
    }

    private int maxOffset() {
        // maxOffset = lines.size() (全部看完) - 但受限於 draw 時的 viewHeight
        return Math.max(0, lines.size());
    }

    private void scrollToBottomIfAutoFollow() {
        if (autoFollow) {
            scrollOffset = maxOffset();
        }
    }

    /**
     * 自訂繪製邏輯：底部對齊渲染。
     *
     * 設計說明：
     * - 當內容少於 view 高度時：上方留空，內容靠底部（如啟動時 banner 在底部）
     * - 當內容超過 view 高度時：根據 scrollOffset 顯示對應範圍
     * - autoFollow 模式下總是顯示最新內容
     */
    private Rectangle drawContent(Screen screen, Rectangle rect) {
        int viewHeight = rect.height();
        int viewWidth = rect.width();
        int totalLines = lines.size();

        if (totalLines == 0 || viewHeight == 0) {
            return rect;
        }

        var writer = screen.writerBuilder().build();

        if (totalLines <= viewHeight) {
            // 內容少於 view 高度：底部對齊
            int startRow = viewHeight - totalLines;
            for (int i = 0; i < totalLines; i++) {
                writer.text(lines.get(i), rect.x(), rect.y() + startRow + i);
            }
        } else {
            // 內容超過 view 高度：根據 scrollOffset 決定顯示範圍
            // scrollOffset 表示「最底部對齊時的偏移」，但這裡用更簡單的方式：
            // 在 autoFollow 時，顯示最後 viewHeight 行
            int endIndex;
            if (autoFollow) {
                endIndex = totalLines;
            } else {
                endIndex = Math.min(totalLines, scrollOffset);
                if (endIndex < viewHeight) {
                    endIndex = viewHeight;
                }
            }
            int startIndex = Math.max(0, endIndex - viewHeight);

            for (int i = 0; i < viewHeight && (startIndex + i) < totalLines; i++) {
                writer.text(lines.get(startIndex + i), rect.x(), rect.y() + i);
            }
        }

        return rect;
    }
}
