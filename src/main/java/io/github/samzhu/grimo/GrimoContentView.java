package io.github.samzhu.grimo;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Content 區：顯示 banner + 對話紀錄，支援底部對齊渲染和滾動。
 *
 * 設計說明：
 * - 純資料模型 + render() 產出 List<AttributedString>，不接觸 Terminal
 * - 底部對齊渲染：內容少於 view 高度時，上方留空，內容靠底部顯示
 * - 內容超過 view 高度時，根據 scrollOffset 決定顯示範圍
 * - autoFollow 模式下新內容自動滾到底部
 * - 渲染統一由 GrimoScreen → Display.update() 處理（diff-based，不閃爍）
 */
public class GrimoContentView {

    /** 品牌標誌色 steel blue（ANSI 256 色碼 67, #5F87AF） */
    private static final int BRAND_COLOR = 67;
    private static final AttributedStyle DARK_BG = AttributedStyle.DEFAULT.background(236);
    private static final AttributedStyle DEFAULT_STYLE = AttributedStyle.DEFAULT;
    private static final AttributedStyle BRAND_STYLE = AttributedStyle.DEFAULT.foreground(BRAND_COLOR);

    private final List<AttributedString> lines = new ArrayList<>();
    private int scrollOffset = 0;
    private boolean autoFollow = true;

    /**
     * 初始化 banner 文字（純 ANSI 字串，逐行加入 lines）。
     */
    public void setBannerText(String bannerText) {
        for (String line : bannerText.split("\n")) {
            // fromAnsi() 正確解析 ANSI escape codes 為 style 資訊
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
        return Math.max(0, lines.size());
    }

    private void scrollToBottomIfAutoFollow() {
        if (autoFollow) {
            scrollOffset = maxOffset();
        }
    }

    /**
     * 渲染 content 區域為 List<AttributedString>。
     *
     * @param cols 終端機寬度（用於截斷超寬行）
     * @param viewHeight content 區可用行數
     * @return 固定 viewHeight 行的列表
     */
    public List<AttributedString> render(int cols, int viewHeight) {
        List<AttributedString> result = new ArrayList<>(viewHeight);
        int totalLines = lines.size();

        if (totalLines == 0 || viewHeight <= 0) {
            for (int i = 0; i < viewHeight; i++) {
                result.add(AttributedString.EMPTY);
            }
            return result;
        }

        if (totalLines <= viewHeight) {
            // 底部對齊：上方填空白
            for (int i = 0; i < viewHeight - totalLines; i++) {
                result.add(AttributedString.EMPTY);
            }
            for (var line : lines) {
                result.add(truncate(line, cols));
            }
        } else {
            // 根據 scrollOffset 取可見範圍
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

            for (int i = startIndex; i < startIndex + viewHeight && i < totalLines; i++) {
                result.add(truncate(lines.get(i), cols));
            }
        }

        // 確保行數正確
        while (result.size() < viewHeight) {
            result.add(AttributedString.EMPTY);
        }
        return result;
    }

    private AttributedString truncate(AttributedString line, int maxWidth) {
        if (line.columnLength() <= maxWidth) {
            return line;
        }
        return line.columnSubSequence(0, maxWidth);
    }
}
