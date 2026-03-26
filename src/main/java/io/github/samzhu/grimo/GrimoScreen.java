package io.github.samzhu.grimo;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;

import java.util.ArrayList;
import java.util.List;

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
public class GrimoScreen {

    private static final int INPUT_HEIGHT = 3;   // separator + input + separator
    private static final int STATUS_HEIGHT = 1;

    private final Display display;
    private final GrimoContentView contentView;
    private final GrimoInputView inputView;
    private final GrimoStatusView statusView;
    private final GrimoSlashMenuView slashMenuView;

    private int rows;
    private int cols;
    private boolean slashMenuVisible = false;

    public GrimoScreen(Terminal terminal, GrimoContentView contentView,
                        GrimoInputView inputView, GrimoStatusView statusView,
                        GrimoSlashMenuView slashMenuView) {
        this.display = new Display(terminal, true);
        this.contentView = contentView;
        this.inputView = inputView;
        this.statusView = statusView;
        this.slashMenuView = slashMenuView;
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

        int contentHeight = Math.max(1, rows - INPUT_HEIGHT - STATUS_HEIGHT);

        List<AttributedString> allLines = new ArrayList<>(rows);

        // 1. Content 區（flex height）
        List<AttributedString> contentLines = contentView.render(cols, contentHeight);

        // 2. Slash menu overlay（覆蓋 content 底部）
        if (slashMenuVisible) {
            List<AttributedString> menuLines = slashMenuView.render(cols);
            int menuHeight = menuLines.size();
            int overlayStart = contentLines.size() - menuHeight;
            for (int i = 0; i < menuHeight; i++) {
                int targetRow = overlayStart + i;
                if (targetRow >= 0 && targetRow < contentLines.size()) {
                    contentLines.set(targetRow, menuLines.get(i));
                }
            }
        }
        allLines.addAll(contentLines);

        // 3. Input 區（固定 3 行）
        allLines.addAll(inputView.render(cols));

        // 4. Status 區（固定 1 行）
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
        int cursorRow = contentHeight + 1; // +1 跳過上方 separator
        int cursorCol = inputView.getCursorCol();
        int cursorPos = cursorRow * cols + cursorCol;

        display.update(allLines, cursorPos);
    }

    /**
     * 強制下次 render 完整重繪（用於初始化或 resize 後）。
     */
    public void clear() {
        display.clear();
        display.reset();
    }

    public int getRows() { return rows; }
    public int getCols() { return cols; }
}
