package io.github.samzhu.grimo;

import org.jline.terminal.Terminal;
import java.io.PrintWriter;
import java.util.Random;

/**
 * 啟動動畫渲染器：5 階段 ANSI 動畫，與實際載入流程透過 CompletableFuture 同步。
 *
 * 設計說明：
 * - Phase 1: 星光 ✦ ✧ · 在左上區域隨機散落
 * - Phase 2: 星光往左上聚集（與最終 banner 位置一致，避免視覺跳動）
 * - Phase 3: 幽靈吉祥物 logo 從左上逐行浮現
 * - Phase 4: 載入進度逐行顯示（由外部 CompletableFuture 驅動）
 * - Phase 5: 清除動畫殘留，只留最終 banner
 * - 非 ANSI 終端（dumb/null）跳過動畫，直接輸出 banner
 *
 * @see <a href="https://en.wikipedia.org/wiki/ANSI_escape_code">ANSI Escape Codes Reference</a>
 */
public class StartupAnimationRenderer {

    private static final String CYAN = "\033[38;5;37m";
    private static final String GOLD = "\033[38;5;178m";
    private static final String WHITE = "\033[1;37m";
    private static final String GREEN = "\033[38;5;34m";
    private static final String RED = "\033[38;5;196m";
    private static final String GRAY = "\033[38;5;245m";
    private static final String RESET = "\033[0m";

    private static final String HIDE_CURSOR = "\033[?25l";
    private static final String SHOW_CURSOR = "\033[?25h";
    private static final String CLEAR_SCREEN = "\033[H\033[2J";

    private static final String[] STAR_CHARS = {"✦", "✧", "·", "✦", "✧"};
    private static final String[] MASCOT_LINES = {
        "    ✦",
        "  ▄████▄",
        "  █●██●█",
        "  ██████",
        "  ▀▄▀▀▄▀"
    };

    private static final long FRAME_DELAY_MS = 80;

    // 動畫定位在左上角，與最終 banner 位置一致，避免動畫結束時視覺跳動
    private static final int MASCOT_ROW = 3;
    private static final int MASCOT_COL = 4;

    private final Terminal terminal;
    private final PrintWriter writer;
    private final int width;
    private final int height;
    private final Random random = new Random();

    public StartupAnimationRenderer(Terminal terminal) {
        this.terminal = terminal;
        this.writer = terminal.writer();
        this.width = Math.max(terminal.getWidth(), 40);
        this.height = Math.max(terminal.getHeight(), 20);
    }

    /**
     * 判斷終端是否支援 ANSI 動畫。
     * dumb 與 null 終端不支援 ANSI escape codes，回傳 false 以跳過動畫。
     *
     * @param terminalType 終端類型字串（如 "xterm-256color"、"dumb"）
     * @return true 表示支援動畫
     */
    public static boolean isAnimationSupported(String terminalType) {
        if (terminalType == null) return false;
        return !terminalType.equals("dumb") && !terminalType.equals("null");
    }

    /**
     * 格式化載入步驟的顯示文字，包含成功/失敗圖示。
     *
     * @param label   步驟名稱（如 "Detecting agents"）
     * @param detail  步驟細節（如 "claude-cli" 或錯誤訊息）
     * @param success true 顯示 ✓，false 顯示 ✗
     * @return 格式化後的 ANSI 彩色字串
     */
    public static String formatLoadingStep(String label, String detail, boolean success) {
        String icon = success ? GREEN + "✓" + RESET : RED + "✗" + RESET;
        return GOLD + "  ✦ " + RESET + GRAY + String.format("%-22s", label + "...")
            + RESET + " " + GRAY + detail + " " + icon + RESET;
    }

    /**
     * 播放 Phase 1-3 的開場動畫：星光散落、聚集、logo 浮現。
     */
    public void playIntroAnimation() {
        writer.print(HIDE_CURSOR);
        writer.print(CLEAR_SCREEN);
        writer.flush();

        // Phase 1: 星光在左上區域散落（限制在 row 1-12, col 1-40 的範圍內）
        int[][] starPositions = new int[15][2];
        int scatterHeight = Math.min(12, height - 2);
        int scatterWidth = Math.min(40, width - 2);
        for (int i = 0; i < starPositions.length; i++) {
            starPositions[i][0] = random.nextInt(scatterHeight) + 1;
            starPositions[i][1] = random.nextInt(scatterWidth) + 1;
        }
        for (int frame = 0; frame < 6; frame++) {
            int starsToShow = (frame + 1) * 3;
            for (int i = 0; i < Math.min(starsToShow, starPositions.length); i++) {
                moveCursor(starPositions[i][0], starPositions[i][1]);
                writer.print(GOLD + STAR_CHARS[i % STAR_CHARS.length] + RESET);
            }
            writer.flush();
            sleep(FRAME_DELAY_MS);
        }

        // Phase 2: 星光往左上角 mascot 位置聚集
        for (int frame = 0; frame < 6; frame++) {
            writer.print(CLEAR_SCREEN);
            float progress = (frame + 1) / 6.0f;
            for (int i = 0; i < starPositions.length; i++) {
                int targetRow = MASCOT_ROW + (i % 5);
                int targetCol = MASCOT_COL + (i % 8);
                int row = starPositions[i][0] + (int)((targetRow - starPositions[i][0]) * progress);
                int col = starPositions[i][1] + (int)((targetCol - starPositions[i][1]) * progress);
                moveCursor(Math.max(1, row), Math.max(1, col));
                writer.print(GOLD + STAR_CHARS[i % STAR_CHARS.length] + RESET);
            }
            writer.flush();
            sleep(FRAME_DELAY_MS);
        }

        // Phase 3: Logo 在左上角逐行浮現
        writer.print(CLEAR_SCREEN);
        writer.flush();
        for (int i = 0; i < MASCOT_LINES.length; i++) {
            int row = MASCOT_ROW + i;
            moveCursor(row, MASCOT_COL);
            String color = (i == 0) ? GOLD : CYAN;
            String line = MASCOT_LINES[i];
            if (line.contains("●")) {
                writer.print(CYAN + line.replace("●", WHITE + "●" + CYAN) + RESET);
            } else {
                writer.print(color + line + RESET);
            }
            writer.flush();
            sleep(FRAME_DELAY_MS);
        }
    }

    /**
     * 在 logo 下方顯示一行載入步驟（Phase 4）。
     *
     * @param stepIndex 步驟序號（0-based），決定顯示的行位置
     * @param text      已格式化的步驟文字（通常由 formatLoadingStep 產生）
     */
    public void showLoadingStep(int stepIndex, String text) {
        int row = MASCOT_ROW + MASCOT_LINES.length + 1 + stepIndex;
        moveCursor(row, MASCOT_COL);
        writer.print(text);
        writer.flush();
    }

    /**
     * 清除動畫畫面並恢復游標（Phase 5）。
     */
    public void clearAnimation() {
        writer.print(CLEAR_SCREEN);
        writer.print(SHOW_CURSOR);
        writer.flush();
    }

    private void moveCursor(int row, int col) {
        writer.printf("\033[%d;%dH", row, col);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
