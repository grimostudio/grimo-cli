package io.github.samzhu.grimo;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * 自建輸入元件：純資料模型 + render() 產出 List<AttributedString>。
 *
 * 設計說明：
 * - 支援 getText()/setText()/insertChar()/deleteChar()/游標移動
 * - 顯示 ❯ 前綴 + 使用者輸入文字
 * - 上下各有分隔線（共 3 行高）
 * - 渲染統一由 GrimoScreen → Display.update() 處理
 */
public class GrimoInputView {

    /** 品牌標誌色 steel blue */
    private static final AttributedStyle BRAND_STYLE = AttributedStyle.DEFAULT.foreground(67);
    private static final AttributedStyle SEPARATOR_STYLE = AttributedStyle.DEFAULT.foreground(245);
    private static final String PROMPT = "❯ ";

    private final StringBuilder buffer = new StringBuilder();
    private int cursorPos = 0;

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
            int tokenStart = buffer.substring(0, cursorPos).lastIndexOf(token);
            buffer.replace(tokenStart, cursorPos, "/" + commandName + " ");
            cursorPos = tokenStart + commandName.length() + 2;
        }
    }

    /**
     * 將輸入文字加入 AttributedStringBuilder，斜線指令 token 以品牌色渲染。
     *
     * 設計說明：
     * - 偵測 /xxx token（行首或空格後的 /word）
     * - 斜線指令名以品牌標誌色（#5F87AF）顯示，其餘文字用預設色
     * - 仿 Claude Code input 區的斜線指令顏色標記
     */
    private void appendStyledInput(AttributedStringBuilder sb) {
        String text = buffer.toString();
        int i = 0;
        while (i < text.length()) {
            // 檢查是否為斜線指令 token 起始位置（行首或空格後的 /）
            if (text.charAt(i) == '/' && (i == 0 || text.charAt(i - 1) == ' ')) {
                // 找到 token 結尾（空格或字串結尾）
                int end = text.indexOf(' ', i);
                if (end == -1) end = text.length();
                sb.styled(BRAND_STYLE, text.substring(i, end));
                i = end;
            } else {
                sb.append(text.charAt(i));
                i++;
            }
        }
    }

    /**
     * 取得游標在 input 行中的欄位置（含 prompt 寬度）。
     */
    public int getCursorCol() {
        return PROMPT.length() + cursorPos;
    }

    /**
     * 渲染 input 區域為 List<AttributedString>（3 行：separator + input + separator）。
     *
     * @param cols 終端機寬度
     * @return 固定 3 行的列表
     */
    public List<AttributedString> render(int cols) {
        List<AttributedString> result = new ArrayList<>(3);
        String separator = "─".repeat(cols);

        // 上方分隔線
        result.add(new AttributedString(separator, SEPARATOR_STYLE));

        // ❯ 前綴 + 輸入文字（斜線指令以品牌色渲染，仿 Claude Code）
        var sb = new AttributedStringBuilder();
        sb.styled(BRAND_STYLE, PROMPT);
        appendStyledInput(sb);
        var inputLine = sb.toAttributedString();
        if (inputLine.columnLength() > cols) {
            inputLine = inputLine.columnSubSequence(0, cols);
        }
        result.add(inputLine);

        // 下方分隔線
        result.add(new AttributedString(separator, SEPARATOR_STYLE));

        return result;
    }
}
