package io.github.samzhu.grimo.tui.screen;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Display;

import io.github.samzhu.grimo.tui.core.Layout;
import io.github.samzhu.grimo.tui.core.Renderable;
import io.github.samzhu.grimo.tui.overlay.McpPanel;
import io.github.samzhu.grimo.tui.overlay.SlashMenu;
import io.github.samzhu.grimo.tui.selection.SelectionRange;
import io.github.samzhu.grimo.tui.selection.TextSelection;
import io.github.samzhu.grimo.tui.view.ContentView;
import io.github.samzhu.grimo.tui.view.InputView;
import io.github.samzhu.grimo.tui.view.StatusView;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 畫面組合器：收集各 View 的 render 結果，組合成完整畫面。
 *
 * 設計說明：
 * - 使用 JLine Display 做 diff-based 渲染（只重繪變化的字元，不閃爍）
 * - 佈局：content(flex) + input(3行) + status(1行)
 * - Slash menu 以 overlay 覆蓋 content 底部
 * - 所有 View 只產出 List<AttributedString>，本類統一呼叫 display.update()
 * - 參考 JLine Tmux.java 的渲染模式
 *
 * @see <a href="https://github.com/jline/jline3/blob/master/terminal/src/main/java/org/jline/utils/Display.java">JLine Display</a>
 */
public class Screen {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Screen.class);

    private static final int INPUT_HEIGHT = 3;   // separator + input + separator
    private static final int STATUS_HEIGHT = 1;

    private final Display display;
    private final ContentView contentView;
    private final InputView inputView;
    private final StatusView statusView;
    private final SlashMenu slashMenu;
    private final McpPanel mcpPanel;
    private final TextSelection textSelection;

    private int rows;
    private int cols;
    private int contentViewportStart;
    private int wrappedContentSize;
    private List<BufferLine> screenBuffer;
    private volatile boolean slashMenuVisible = false;
    private volatile boolean mcpManagerVisible = false;
    private volatile Renderable selectOverlay;
    private volatile boolean forceFullRedraw = false;

    public Screen(Terminal terminal, ContentView contentView,
                   InputView inputView, StatusView statusView,
                   SlashMenu slashMenu,
                   McpPanel mcpPanel,
                   TextSelection textSelection) {
        this.display = new Display(terminal, true);
        this.contentView = contentView;
        this.inputView = inputView;
        this.statusView = statusView;
        this.slashMenu = slashMenu;
        this.mcpPanel = mcpPanel;
        this.textSelection = textSelection;
    }

    public void resize(Size size) {
        this.rows = size.getRows();
        this.cols = size.getColumns();
        display.resize(rows, cols);
    }

    public void setSlashMenuVisible(boolean visible) {
        this.slashMenuVisible = visible;
    }

    public boolean isSlashMenuVisible() {
        return slashMenuVisible;
    }

    public void setMcpManagerVisible(boolean visible) {
        this.mcpManagerVisible = visible;
    }

    public boolean isMcpManagerVisible() {
        return mcpManagerVisible;
    }

    /**
     * 設定 select overlay（ListSelect 或 GroupedSelect）。
     * 設定時自動關閉其他 overlay（互斥）。
     */
    public void setSelectOverlay(Renderable overlay) {
        this.slashMenuVisible = false;
        this.mcpManagerVisible = false;
        this.selectOverlay = overlay;
    }

    public void clearSelectOverlay() {
        this.selectOverlay = null;
    }

    public boolean hasSelectOverlay() {
        return selectOverlay != null;
    }

    /**
     * 組裝統一 buffer（content + input + status）。
     * 供 TextSelection.finish() 使用。
     */
    private void assembleScreenBuffer(int contentHeight) {
        var bufferLines = contentView.getBufferLines(cols);
        wrappedContentSize = bufferLines.size();
        contentViewportStart = contentView.getViewportStart(cols, contentHeight);
        screenBuffer = new ArrayList<>(bufferLines);
        // input 文字行（3 行中只取中間那行）
        var inputLines = inputView.render(cols);
        if (inputLines.size() >= 3) {
            screenBuffer.add(BufferLine.of(inputLines.get(1)));
        }
        // status 行
        var statusLines = statusView.render(cols);
        if (!statusLines.isEmpty()) {
            screenBuffer.add(BufferLine.of(statusLines.getFirst()));
        }
    }

    /**
     * 螢幕行號 → buffer 行號。不可選行回傳 -1。
     *
     * 設計說明：Content 區底部對齊 — 內容少於 viewport 時上方有空白 padding。
     * 例如 12 行內容在 32 行 viewport 中，screenRow 0-19 是空白，20-31 才有內容。
     * 必須扣除 padding 行數才能正確映射到 buffer index。
     */
    public int screenToBuffer(int screenRow, int contentHeight) {
        if (screenRow < contentHeight) {
            // 計算底部對齊的 padding：content 區上方的空白行數
            int paddingRows = Math.max(0, contentHeight - wrappedContentSize);
            if (screenRow < paddingRows) {
                return -1; // 空白 padding 行，不可選
            }
            return contentViewportStart + (screenRow - paddingRows);
        }
        int inputSepTop = contentHeight;
        int inputTextRow = contentHeight + 1;
        int inputSepBot = contentHeight + 2;
        int statusRow = rows - 1;
        if (screenRow == inputSepTop || screenRow == inputSepBot) {
            return -1;
        }
        if (screenRow == inputTextRow) {
            return wrappedContentSize;
        }
        if (screenRow == statusRow) {
            return wrappedContentSize + 1;
        }
        return -1;
    }

    public List<BufferLine> getScreenBuffer() {
        return screenBuffer;
    }

    public int getContentViewportStart() {
        return contentViewportStart;
    }

    /**
     * 對選取範圍的行加上 inverse style。
     * 設計說明：用 AttributedStyle.INVERSE 做反白（所有終端支援 ANSI \033[7m）
     */
    private List<AttributedString> applySelectionHighlight(
            List<AttributedString> screenLines, int contentHeight) {
        SelectionRange range = textSelection.getRange();
        if (range == null) return screenLines;

        var result = new ArrayList<>(screenLines);
        for (int screenRow = 0; screenRow < screenLines.size(); screenRow++) {
            int bufferRow = screenToBuffer(screenRow, contentHeight);
            if (bufferRow < 0) continue;
            var line = screenLines.get(screenRow);
            var span = range.colsForRow(bufferRow, line.columnLength());
            if (span.isEmpty()) continue;
            result.set(screenRow, applyInvertStyle(line, span.get().start(), span.get().end()));
        }
        return result;
    }

    /**
     * 對一行的指定列範圍套用 inverse style。
     * columnSubSequence 已處理 CJK 雙寬字元邊界。
     */
    private AttributedString applyInvertStyle(AttributedString line, int startCol, int endCol) {
        var builder = new AttributedStringBuilder();
        builder.append(line.columnSubSequence(0, startCol));
        builder.styled(AttributedStyle.INVERSE,
                line.columnSubSequence(startCol, endCol).toString());
        builder.append(line.columnSubSequence(endCol, line.columnLength()));
        return builder.toAttributedString();
    }

    /**
     * 渲染完整畫面：組合各區域的 AttributedString 行，呼叫 Display.update()。
     *
     * 設計說明：
     * - Display.update() 會比對前一次的行內容，只輸出有差異的部分
     * - 如果某行完全沒變，Display 不會發送任何 ANSI 序列（零成本）
     * - 這解決了 Spring Shell TerminalUI 每次事件都全螢幕重繪的閃爍問題
     */
    public void render() {
        if (rows <= 0 || cols <= 0) return;

        // 設計說明：使用 Layout.vertical() 替換手算，與 TUI framework 一致
        // 借鑑 Ratatui Layout + OpenCode flexGrow
        int[] heights = Layout.vertical(rows, 0,
                new Layout.Fill(),                    // content（填滿剩餘）
                new Layout.Fixed(INPUT_HEIGHT),       // input（固定 3 行）
                new Layout.Fixed(STATUS_HEIGHT));     // status（固定 1 行）
        int contentHeight = Math.max(1, heights[0]);

        List<AttributedString> allLines = new ArrayList<>(rows);

        // 1. Content 區（flex height）
        List<AttributedString> contentLines = contentView.render(cols, contentHeight);

        // 2. Slash menu overlay（覆蓋 content 底部）
        if (slashMenuVisible) {
            List<AttributedString> menuLines = slashMenu.render(cols);
            int menuHeight = menuLines.size();
            int overlayStart = contentLines.size() - menuHeight;
            for (int i = 0; i < menuHeight; i++) {
                int targetRow = overlayStart + i;
                if (targetRow >= 0 && targetRow < contentLines.size()) {
                    contentLines.set(targetRow, menuLines.get(i));
                }
            }
        }

        // 3. MCP Manager overlay（覆蓋 content 底部，與 slash menu 互斥）
        if (mcpManagerVisible) {
            List<AttributedString> managerLines = mcpPanel.render(cols);
            int managerHeight = managerLines.size();
            int overlayStart = contentLines.size() - managerHeight;
            for (int i = 0; i < managerHeight; i++) {
                int targetRow = overlayStart + i;
                if (targetRow >= 0 && targetRow < contentLines.size()) {
                    contentLines.set(targetRow, managerLines.get(i));
                }
            }
        }

        // 4. Select overlay (ListSelect / GroupedSelect)
        if (selectOverlay != null) {
            List<AttributedString> selectLines = selectOverlay.render(cols);
            int selectHeight = selectLines.size();
            int overlayStart = contentLines.size() - selectHeight;
            for (int i = 0; i < selectHeight; i++) {
                int targetRow = overlayStart + i;
                if (targetRow >= 0 && targetRow < contentLines.size()) {
                    contentLines.set(targetRow, selectLines.get(i));
                }
            }
        }

        allLines.addAll(contentLines);

        // 4. Input 區（固定 3 行）
        allLines.addAll(inputView.render(cols));

        // 5. Status 區（固定 1 行）
        allLines.addAll(statusView.render(cols));

        // 確保填滿螢幕高度
        while (allLines.size() < rows) {
            allLines.add(AttributedString.EMPTY);
        }
        // 截斷到螢幕高度（避免 Display 超出邊界）
        if (allLines.size() > rows) {
            allLines = new ArrayList<>(allLines.subList(0, rows));
        }

        // 游標位置：input 區第二行（separator 之後），prompt + cursorPos
        // Display 內部用 columns1 = columns + 1 作為每行邏輯寬度（含行尾換行符）
        // 參考 JLine Size.cursorPos(): row * (cols + 1) + col
        int cursorRow = contentHeight + 1; // +1 跳過上方 separator
        int cursorCol = inputView.getCursorCol();
        int cursorPos = cursorRow * (cols + 1) + cursorCol;

        // 組裝 buffer（供 selection text extraction 和 highlight 用）
        assembleScreenBuffer(contentHeight);

        // 選取反白渲染
        allLines = applySelectionHighlight(allLines, contentHeight);

        // 強制全螢幕重繪（解決 Display diff 在 content 大量變動時不更新 input 行的問題）
        // 設計說明：JLine Display.update() 的 diff 演算法在多行同時變化時可能遺漏部分行更新
        // 當 content 區新增行（如送出訊息後加入 user input + thinking 行）時，觸發 clear() 重繪
        if (forceFullRedraw) {
            display.clear();
            forceFullRedraw = false;
        }
        display.update(allLines, cursorPos);
    }

    /**
     * 強制下次 render 完整重繪（用於初始化或 resize 後）。
     */
    public void clear() {
        display.clear();
        display.reset();
    }

    /**
     * 請求下次 render 時做完整重繪（不依賴 Display diff）。
     * 用於 content 大量變動後確保 input 行正確更新。
     */
    public void requestFullRedraw() {
        forceFullRedraw = true;
    }

    public int getRows() { return rows; }
    public int getCols() { return cols; }
}
