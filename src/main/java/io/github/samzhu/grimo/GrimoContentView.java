package io.github.samzhu.grimo;

import io.github.samzhu.grimo.tui.screen.BufferLine;
import io.github.samzhu.grimo.tui.core.Renderable;
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
public class GrimoContentView implements Renderable {

    /** 品牌標誌色 steel blue（ANSI 256 色碼 67, #5F87AF） */
    private static final int BRAND_COLOR = 67;
    private static final AttributedStyle DARK_BG = AttributedStyle.DEFAULT.background(236);
    private static final AttributedStyle DEFAULT_STYLE = AttributedStyle.DEFAULT;
    private static final AttributedStyle BRAND_STYLE = AttributedStyle.DEFAULT.foreground(BRAND_COLOR);

    private final List<AttributedString> lines = new ArrayList<>();
    private int scrollOffset = 0;
    private boolean autoFollow = true;

    /** 完整 wrapped line cache（所有歷史行 wrap 後的結果） */
    private List<BufferLine> wrappedCache = new ArrayList<>();
    private int cachedCols = -1;

    /**
     * 初始化 banner 文字（純 ANSI 字串，逐行加入 lines）。
     */
    public synchronized void setBannerText(String bannerText) {
        for (String line : bannerText.split("\n")) {
            // fromAnsi() 正確解析 ANSI escape codes 為 style 資訊
            lines.add(AttributedString.fromAnsi(line));
        }
    }

    /**
     * 附加使用者輸入：❯ 前綴 + 深色背景行。
     */
    public synchronized void appendUserInput(String text) {
        var sb = new AttributedStringBuilder();
        sb.styled(BRAND_STYLE, "❯ ");
        sb.styled(DARK_BG, text);
        lines.add(sb.toAttributedString());
        lines.add(AttributedString.EMPTY);
        scrollToBottomIfAutoFollow();
        incrementalCacheUpdate(sb.toAttributedString());
        incrementalCacheUpdate(AttributedString.EMPTY);
    }

    /**
     * 附加 AI 回覆：⏺ 前綴 + 一般文字。
     */
    public synchronized void appendAiReply(String text) {
        var sb = new AttributedStringBuilder();
        sb.styled(BRAND_STYLE, "⏺ ");
        sb.append(text);
        lines.add(sb.toAttributedString());
        lines.add(AttributedString.EMPTY);
        scrollToBottomIfAutoFollow();
        incrementalCacheUpdate(sb.toAttributedString());
        incrementalCacheUpdate(AttributedString.EMPTY);
    }

    /**
     * 附加命令輸出：直接顯示（無前綴），每行加 2 格縮排。
     */
    public synchronized void appendCommandOutput(String text) {
        for (String line : text.split("\n")) {
            var as = new AttributedString("  " + line);
            lines.add(as);
            incrementalCacheUpdate(as);
        }
        lines.add(AttributedString.EMPTY);
        scrollToBottomIfAutoFollow();
        incrementalCacheUpdate(AttributedString.EMPTY);
    }

    /**
     * 附加錯誤訊息：⚠ 前綴 + 紅色文字。
     */
    public synchronized void appendError(String text) {
        var sb = new AttributedStringBuilder();
        sb.styled(AttributedStyle.DEFAULT.foreground(196), "⚠ " + text);
        lines.add(sb.toAttributedString());
        lines.add(AttributedString.EMPTY);
        scrollToBottomIfAutoFollow();
        incrementalCacheUpdate(sb.toAttributedString());
        incrementalCacheUpdate(AttributedString.EMPTY);
    }

    /**
     * 附加一行原始 AttributedString（用於 streaming 等特殊場景）。
     */
    public synchronized void appendLine(AttributedString line) {
        lines.add(line);
        scrollToBottomIfAutoFollow();
        incrementalCacheUpdate(line);
    }

    /**
     * 移除最後一行（用於移除 "thinking..." 等暫時狀態行）。
     */
    public synchronized void removeLastLine() {
        if (!lines.isEmpty()) {
            lines.removeLast();
        }
    }

    /**
     * 清空所有內容。
     */
    public synchronized void clear() {
        lines.clear();
        scrollOffset = 0;
        autoFollow = true;
        wrappedCache.clear();
        cachedCols = -1;
    }

    // === 滾動控制 ===

    public synchronized void scrollUp(int count) {
        scrollOffset = Math.max(0, scrollOffset - count);
        autoFollow = false;
    }

    public synchronized void scrollDown(int count) {
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
     * 重建 wrapped line cache。
     * 設計說明：對所有 lines 做 columnSplitLength(cols)，建立 BufferLine list。
     * wrapped=true 標記同一原始行的延續行（TextSelection 擷取文字時不加 \n）。
     */
    private void rebuildWrappedCache(int cols) {
        wrappedCache = new ArrayList<>();
        for (var line : lines) {
            if (line.columnLength() <= cols) {
                wrappedCache.add(BufferLine.of(line));
            } else {
                var splits = line.columnSplitLength(cols);
                for (int j = 0; j < splits.size(); j++) {
                    wrappedCache.add(j == 0
                            ? BufferLine.of(splits.get(j))
                            : BufferLine.wrapped(splits.get(j)));
                }
            }
        }
        cachedCols = cols;
    }

    private void incrementalCacheUpdate(AttributedString newLine) {
        if (cachedCols > 0) {
            if (newLine.columnLength() <= cachedCols) {
                wrappedCache.add(BufferLine.of(newLine));
            } else {
                var splits = newLine.columnSplitLength(cachedCols);
                for (int j = 0; j < splits.size(); j++) {
                    wrappedCache.add(j == 0
                            ? BufferLine.of(splits.get(j))
                            : BufferLine.wrapped(splits.get(j)));
                }
            }
        }
    }

    /**
     * 取得完整 wrapped line cache（供 GrimoScreen 組裝統一 buffer）。
     */
    public synchronized List<BufferLine> getBufferLines(int cols) {
        if (cachedCols != cols) {
            rebuildWrappedCache(cols);
        }
        return List.copyOf(wrappedCache);
    }

    /**
     * 取得 viewport 起始行在 wrapped cache 中的 index。
     * 用於 screenToBuffer 轉換。
     *
     * 設計說明：scrollOffset 是 unwrapped lines 索引，需轉換為 wrapped cache 索引。
     * 複製 render() 的 startIndex 邏輯確保座標對齊。
     */
    public synchronized int getViewportStart(int cols, int viewHeight) {
        if (cachedCols != cols) {
            rebuildWrappedCache(cols);
        }
        int totalLines = lines.size();
        if (totalLines == 0 || viewHeight <= 0) return 0;

        // 複製 render() 的 startIndex 計算邏輯（unwrapped 座標）
        int startIndex;
        if (totalLines <= viewHeight) {
            startIndex = 0;
        } else if (autoFollow) {
            startIndex = Math.max(0, totalLines - viewHeight);
        } else {
            int endIndex = Math.min(totalLines, scrollOffset);
            if (endIndex < viewHeight) endIndex = viewHeight;
            startIndex = Math.max(0, endIndex - viewHeight);
        }

        // 把 unwrapped startIndex 轉成 wrappedCache 索引
        return unwrappedToWrappedIndex(startIndex);
    }

    /**
     * 把 unwrapped line 索引轉為 wrappedCache 中的行號。
     * 遍歷 wrappedCache，計數非 wrapped 行（每個代表一個 unwrapped line 的開頭）。
     */
    private int unwrappedToWrappedIndex(int unwrappedIndex) {
        int unwrapped = 0;
        for (int i = 0; i < wrappedCache.size(); i++) {
            if (unwrapped == unwrappedIndex) return i;
            if (i + 1 < wrappedCache.size() && !wrappedCache.get(i + 1).wrapped()) {
                unwrapped++;
            } else if (i + 1 >= wrappedCache.size()) {
                unwrapped++;
            }
        }
        return wrappedCache.size();
    }

    @Override
    public synchronized List<AttributedString> render(int width) {
        // Renderable 契約：回傳所有內容（自然高度），容器負責捲動
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
    public synchronized List<AttributedString> render(int cols, int viewHeight) {
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
