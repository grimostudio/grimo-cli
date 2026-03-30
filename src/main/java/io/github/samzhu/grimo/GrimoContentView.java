package io.github.samzhu.grimo;

import io.github.samzhu.grimo.shared.tui.TuiComponent;
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
public class GrimoContentView implements TuiComponent {

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
     * 移除最後一行（用於移除 "thinking..." 等暫時狀態行）。
     */
    public void removeLastLine() {
        if (!lines.isEmpty()) {
            lines.removeLast();
        }
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

    @Override
    public List<AttributedString> render(int width) {
        // TuiComponent 契約：回傳所有內容（自然高度），容器負責捲動
        return render(width, Integer.MAX_VALUE);
    }

    /**
     * 渲染 content 區域為 List<AttributedString>。
     *
     * 設計說明：
     * - 超寬行用 columnSplitLength(cols) wrap（不截斷），保證內容不被吃掉
     * - 底部對齊：內容少時上方填空白，多時只顯示最後 viewHeight 行
     * - scroll offset 索引 stored lines，render 時動態 wrap
     * - 借鑑 OpenCode scrollbox stickyScroll=true 的底部吸附行為
     *
     * @param cols 終端機寬度（用於 wrap 超寬行）
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

        // 取要顯示的 stored lines 範圍
        int endIndex, startIndex;
        if (totalLines <= viewHeight) {
            startIndex = 0;
            endIndex = totalLines;
        } else if (autoFollow) {
            endIndex = totalLines;
            startIndex = Math.max(0, endIndex - viewHeight);
        } else {
            endIndex = Math.min(totalLines, scrollOffset);
            if (endIndex < viewHeight) endIndex = viewHeight;
            startIndex = Math.max(0, endIndex - viewHeight);
        }

        // 將 visible stored lines wrap 到 cols（不截斷）
        var wrappedVisible = new ArrayList<AttributedString>();
        for (int i = startIndex; i < endIndex && i < totalLines; i++) {
            var line = lines.get(i);
            if (line.columnLength() <= cols) {
                wrappedVisible.add(line);
            } else {
                wrappedVisible.addAll(line.columnSplitLength(cols));
            }
        }

        // 底部對齊
        if (wrappedVisible.size() < viewHeight) {
            for (int i = 0; i < viewHeight - wrappedVisible.size(); i++) {
                result.add(AttributedString.EMPTY);
            }
            result.addAll(wrappedVisible);
        } else {
            // 取最後 viewHeight 行（底部對齊，自動捲到最新）
            int skip = wrappedVisible.size() - viewHeight;
            result.addAll(wrappedVisible.subList(skip, wrappedVisible.size()));
        }

        while (result.size() < viewHeight) {
            result.add(AttributedString.EMPTY);
        }
        return result;
    }
}
