package io.github.samzhu.grimo;

import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 自製互動式斜線命令選單，取代 JLine 內建補全的醜陋多欄顯示。
 *
 * 設計說明：
 * - 仿 Claude Code CLI 的 / 選單 UX：乾淨的單欄清單、即時過濾、方向鍵導航
 * - 用 ANSI escape codes 在 prompt 下方繪製選單，完全控制外觀
 * - 接管終端輸入：字元=即時過濾、↑↓=移動選取、Enter/Tab=確認、Esc=取消
 * - 使用行數追蹤 + cursor up/down 導航（非 save/restore，避免單一 slot 衝突）
 * - 選單關閉後清除所有繪製的行，還原終端狀態
 *
 * @see <a href="https://en.wikipedia.org/wiki/ANSI_escape_code">ANSI Escape Codes Reference</a>
 */
public class SlashMenuRenderer {

    /** 選單項目：指令名稱 + 說明文字 */
    public record MenuItem(String command, String description) {}

    private static final String CYAN = "\033[38;5;37m";
    private static final String GRAY = "\033[38;5;245m";
    private static final String WHITE_BOLD = "\033[1;37m";
    private static final String DIM = "\033[2m";
    private static final String RESET = "\033[0m";
    private static final String CLEAR_LINE = "\033[K";

    private static final int MAX_VISIBLE = 10;

    private final Terminal terminal;
    private final PrintWriter writer;
    private final NonBlockingReader reader;
    private final StatusLineRenderer statusLine;

    /** 目前選單佔用的行數（用於清除和游標回移） */
    private int drawnLines = 0;

    public SlashMenuRenderer(Terminal terminal, StatusLineRenderer statusLine) {
        this.terminal = terminal;
        this.writer = terminal.writer();
        this.reader = terminal.reader();
        this.statusLine = statusLine;
    }

    /**
     * 顯示互動式選單，阻塞直到使用者選取或取消。
     *
     * @param items 所有可選的指令項目
     * @return 選取的指令名稱（如 "agent list"），取消時回傳 null
     */
    public String show(List<MenuItem> items) {
        StringBuilder filter = new StringBuilder();
        int selectedIndex = 0;
        int scrollOffset = 0;

        if (statusLine != null) {
            statusLine.suspend();
        }

        List<MenuItem> filtered = filterItems(items, filter.toString());
        render(filtered, selectedIndex, scrollOffset, filter.toString());

        try {
            while (true) {
                int c = reader.read();
                if (c == -1) { cleanup(); return null; }

                if (c == 27) { // ESC or arrow key sequence
                    int next = peekChar();
                    if (next == '[') {
                        reader.read(); // consume '['
                        int arrow = reader.read();
                        if (arrow == 'A') { // Up
                            if (selectedIndex > 0) {
                                selectedIndex--;
                                if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
                            }
                        } else if (arrow == 'B') { // Down
                            if (selectedIndex < filtered.size() - 1) {
                                selectedIndex++;
                                if (selectedIndex >= scrollOffset + MAX_VISIBLE) {
                                    scrollOffset = selectedIndex - MAX_VISIBLE + 1;
                                }
                            }
                        }
                    } else {
                        cleanup();
                        return null; // Pure ESC — cancel
                    }
                } else if (c == '\r' || c == '\n' || c == '\t') { // Enter or Tab
                    cleanup();
                    if (!filtered.isEmpty() && selectedIndex < filtered.size()) {
                        return filtered.get(selectedIndex).command();
                    }
                    return null;
                } else if (c == 127 || c == 8) { // Backspace / Delete
                    if (!filter.isEmpty()) {
                        filter.deleteCharAt(filter.length() - 1);
                        filtered = filterItems(items, filter.toString());
                        selectedIndex = 0;
                        scrollOffset = 0;
                    } else {
                        // filter 已空，再按 backspace 等同取消
                        cleanup();
                        return null;
                    }
                } else if (c >= 32 && c < 127) { // Printable char
                    filter.append((char) c);
                    filtered = filterItems(items, filter.toString());
                    selectedIndex = 0;
                    scrollOffset = 0;
                } else {
                    continue;
                }

                render(filtered, selectedIndex, scrollOffset, filter.toString());
            }
        } catch (IOException e) {
            cleanup();
            return null;
        }
    }

    /**
     * 清除舊畫面 → 繪製新選單 → 回到 prompt 行。
     * 完全使用相對行移動（\033[nA / \033[nB），不使用 save/restore（\033[s / \033[u），
     * 避免單一 slot 衝突。用 drawnLines 計數器追蹤已繪製行數。
     */
    private void render(List<MenuItem> filtered, int selectedIndex, int scrollOffset, String filter) {
        // 1. 清除之前畫的選單行
        if (drawnLines > 0) {
            clearDrawnLines();
        }

        // 2. 繪製選單（在 prompt 下方）
        writer.println(); // 換行到選單區域
        drawnLines = 0;

        int visible = Math.min(MAX_VISIBLE, filtered.size() - scrollOffset);
        if (filtered.isEmpty()) {
            writer.print("  " + DIM + "(no matching commands)" + RESET + CLEAR_LINE);
            writer.println();
            drawnLines = 2; // blank line + "no matching" line
        } else {
            for (int i = 0; i < visible; i++) {
                int idx = scrollOffset + i;
                var item = filtered.get(idx);
                boolean selected = (idx == selectedIndex);

                if (selected) {
                    writer.print(CYAN + "❯ " + RESET);
                } else {
                    writer.print("  ");
                }

                String cmdDisplay = "/" + item.command();
                if (selected) {
                    writer.print(WHITE_BOLD + cmdDisplay + RESET);
                } else {
                    writer.print(CYAN + cmdDisplay + RESET);
                }

                int padding = Math.max(1, 28 - cmdDisplay.length());
                writer.print(" ".repeat(padding));
                writer.print(GRAY + truncate(item.description(), 50) + RESET);
                writer.print(CLEAR_LINE);
                writer.println();
            }
            drawnLines = visible + 1; // +1 for the blank line (println before menu)
        }

        // 清除選單下方殘留
        writer.print(CLEAR_LINE);

        // 3. 用相對行移動回到 prompt 行（不使用 \033[s/\033[u）
        if (drawnLines > 0) {
            writer.printf("\033[%dA", drawnLines);
        }
        // 回到行首，然後移到 filter 文字後面（prompt "❯ " 寬度 2 + "/" 寬度 1 + filter 長度）
        writer.print("\r");
        int cursorCol = 2 + 1 + filter.length(); // "❯ " + "/" + filter
        if (cursorCol > 0) {
            writer.printf("\033[%dC", cursorCol);
        }
        writer.flush();
    }

    /**
     * 清除已繪製的選單行。使用相對行移動，不使用 save/restore。
     */
    private void clearDrawnLines() {
        for (int i = 0; i < drawnLines; i++) {
            writer.println();
            writer.print(CLEAR_LINE);
        }
        if (drawnLines > 0) {
            writer.printf("\033[%dA", drawnLines);
        }
        drawnLines = 0;
    }

    /**
     * 最終清理：清除選單、還原游標。
     */
    private void cleanup() {
        if (drawnLines > 0) {
            clearDrawnLines();
        }
        writer.flush();
        if (statusLine != null) {
            statusLine.restore();
        }
    }

    private List<MenuItem> filterItems(List<MenuItem> items, String filter) {
        if (filter.isEmpty()) return items;
        String lower = filter.toLowerCase();
        var result = new ArrayList<MenuItem>();
        for (var item : items) {
            if (item.command().toLowerCase().contains(lower)) {
                result.add(item);
            }
        }
        return result;
    }

    private int peekChar() throws IOException {
        return reader.peek(50);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 1) + "…";
    }
}
