package io.github.samzhu.grimo;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.springframework.shell.jline.tui.component.view.control.BoxView;
import org.springframework.shell.jline.tui.component.view.screen.Screen;
import org.springframework.shell.jline.tui.geom.Position;
import org.springframework.shell.jline.tui.geom.Rectangle;

/**
 * 自建輸入元件：取代 Spring Shell 的 InputView（其缺少 setText()）。
 *
 * 設計說明：
 * - 使用 BoxView + setDrawFunction() 注入自訂繪製邏輯
 * - 支援 getText()/setText()/insertChar()/deleteChar()/游標移動
 * - 顯示 ❯ 前綴 + 使用者輸入文字 + 游標
 * - 未來擴展：多行支援（Shift+Enter）、斜線指令標誌色渲染
 *
 * @see <a href="https://docs.spring.io/spring-shell/reference/tui/views/input.html">InputView :: Spring Shell</a>
 */
public class GrimoInputView extends BoxView {

    /** 品牌標誌色 steel blue */
    private static final AttributedStyle BRAND_STYLE = AttributedStyle.DEFAULT.foreground(67);
    private static final String PROMPT = "❯ ";

    private final StringBuilder buffer = new StringBuilder();
    private int cursorPos = 0;

    public GrimoInputView() {
        setShowBorder(true);
        setDrawFunction(this::drawInput);
    }

    // === 文字操作 ===

    public String getText() {
        return buffer.toString();
    }

    public void setText(String text) {
        buffer.setLength(0);
        buffer.append(text);
        cursorPos = buffer.length();
    }

    public void insertChar(char c) {
        buffer.insert(cursorPos, c);
        cursorPos++;
    }

    public void insertString(String s) {
        buffer.insert(cursorPos, s);
        cursorPos += s.length();
    }

    public void deleteChar() {
        if (cursorPos > 0) {
            buffer.deleteCharAt(cursorPos - 1);
            cursorPos--;
        }
    }

    public void deleteForward() {
        if (cursorPos < buffer.length()) {
            buffer.deleteCharAt(cursorPos);
        }
    }

    public void clear() {
        buffer.setLength(0);
        cursorPos = 0;
    }

    // === 游標移動 ===

    public void moveCursorLeft() {
        if (cursorPos > 0) cursorPos--;
    }

    public void moveCursorRight() {
        if (cursorPos < buffer.length()) cursorPos++;
    }

    public void moveCursorHome() {
        cursorPos = 0;
    }

    public void moveCursorEnd() {
        cursorPos = buffer.length();
    }

    // === 斜線指令偵測 ===

    /**
     * 偵測是否剛輸入「空格+/」或「行首/」，應開啟斜線指令選單。
     */
    public boolean shouldOpenSlashMenu() {
        if (buffer.isEmpty()) return false;
        String text = buffer.toString();
        // 行首 /
        if (cursorPos == 1 && text.charAt(0) == '/') return true;
        // 空格 + /
        if (cursorPos >= 2 && text.charAt(cursorPos - 1) == '/' && text.charAt(cursorPos - 2) == ' ') {
            return true;
        }
        return false;
    }

    /**
     * 取得游標所在的 /xxx token（用於過濾斜線指令選單）。
     * 回傳 null 表示游標不在斜線 token 上。
     */
    public String getCurrentSlashToken() {
        if (buffer.isEmpty()) return null;
        String text = buffer.substring(0, cursorPos);
        // 從游標位置往回找最近的空格或行首
        int start = text.lastIndexOf(' ');
        String token = (start == -1) ? text : text.substring(start + 1);
        if (token.startsWith("/")) {
            return token;
        }
        return null;
    }

    /**
     * 插入斜線指令名（前後加空格），取代目前的 /xxx token。
     */
    public void insertSlashCommand(String commandName) {
        String token = getCurrentSlashToken();
        if (token != null) {
            // 找到 token 起始位置
            int tokenStart = buffer.substring(0, cursorPos).lastIndexOf(token);
            buffer.replace(tokenStart, cursorPos, "/" + commandName + " ");
            cursorPos = tokenStart + commandName.length() + 2; // +2 for "/" and " "
        }
    }

    /**
     * 自訂繪製：顯示 ❯ 前綴 + 文字 + 游標位置。
     */
    private Rectangle drawInput(Screen screen, Rectangle rect) {
        var writer = screen.writerBuilder().color(67).build();
        var defaultWriter = screen.writerBuilder().build();

        // 繪製 ❯ 前綴
        writer.text(PROMPT, rect.x(), rect.y());

        // 繪製使用者輸入文字
        String text = buffer.toString();
        if (!text.isEmpty()) {
            defaultWriter.text(text, rect.x() + PROMPT.length(), rect.y());
        }

        // 設定游標位置（❯ 佔 2 字元寬度 + cursorPos）
        screen.setShowCursor(true);
        screen.setCursorPosition(new Position(rect.x() + PROMPT.length() + cursorPos, rect.y()));

        return rect;
    }
}
