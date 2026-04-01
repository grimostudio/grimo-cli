package io.github.samzhu.grimo.tui.widget;

import io.github.samzhu.grimo.tui.core.DisplayWidth;
import io.github.samzhu.grimo.tui.core.Layout;
import io.github.samzhu.grimo.tui.core.Renderable;
import org.jline.utils.AttributedString;

import java.util.List;

/**
 * 渲染靜態 Banner：左側可愛方塊幽靈吉祥物 + 右側環境狀態。
 *
 * 設計說明：
 * - 左側用 Unicode block characters (█▀▄▐▌) 拼出 5 行高的幽靈造型
 * - 顏色用 ANSI 256 色：青色系 (38;5;30-37) 對應品牌色 #5ba3b5，金色 (38;5;178) 用於星光
 * - 右側 4 行環境狀態：版本、Agent/Model、工作目錄、資源計數
 * - 純函數設計，無 Terminal 依賴，方便單元測試
 *
 * @see <a href="https://en.wikipedia.org/wiki/ANSI_escape_code#8-bit">ANSI 256 Color Reference</a>
 */
public class Banner implements Renderable {

    @Override
    public List<AttributedString> render(int width) {
        // Renderable 契約：BannerRenderer 需要額外參數，此方法供介面相容
        // 實際渲染由 render(version, agentId, ..., cols) 處理
        return List.of();
    }

    // ANSI 256 色碼
    private static final String BRAND = "\033[38;5;67m";    // 品牌色 steel blue（#5F87AF）
    private static final String GOLD = "\033[38;5;178m";    // 金色（星光 #e8c44a）
    private static final String WHITE = "\033[1;37m";       // 白色粗體
    private static final String GRAY = "\033[38;5;245m";    // 灰色
    private static final String RESET = "\033[0m";

    /**
     * 渲染完整 banner 字串（含 ANSI 色碼）。
     *
     * @param version       應用版本號（如 "0.1.0" 或 "dev"）
     * @param agentId       當前使用的 Agent ID（如 "claude-cli"）
     * @param model         當前使用的模型名稱（如 "sonnet"）
     * @param projectPath 當前專案路徑
     * @param agentCount    偵測到的可用 Agent 數量
     * @param skillCount    已載入的 Skill 數量
     * @param mcpCount      已連線的 MCP Server 數量
     * @param taskCount     已排程的 Task 數量
     * @param cols          終端寬度（目前保留供未來動態排版使用）
     * @return 含 ANSI 色碼的多行 banner 字串
     */
    public String render(String version, String agentId, String model,
                         String projectPath, int agentCount, int skillCount, int mcpCount, int taskCount,
                         int cols) {
        // 設計說明：用 Layout.horizontal 將每行切成 mascot + gap + info 三欄
        // mascot 純文字寬度先測量，再套 ANSI 色碼
        // 這樣 DisplayWidth.of() 不會被 ANSI escape 干擾

        // Mascot 純文字（不含 ANSI）
        String[] mascotPlain = {
                "       ✦",       // line 0: star
                "     ▄████▄",    // line 1: head top
                "     █●██●█",    // line 2: eyes
                "     ██████",    // line 3: body
                "     ▀▄▀▀▄▀"    // line 4: feet
        };

        // Mascot ANSI（與 mascotPlain 同順序，含色碼）
        String[] mascotAnsi = {
                GOLD + "       ✦" + RESET,
                BRAND + "     ▄████▄" + RESET,
                BRAND + "     █" + WHITE + "●" + BRAND + "██" + WHITE + "●" + BRAND + "█" + RESET,
                BRAND + "     ██████" + RESET,
                BRAND + "     ▀▄▀▀▄▀" + RESET
        };

        // Info 行（與 mascot 行對齊）
        String[] info = {
                WHITE + "Grimo" + RESET + " " + GRAY + "v" + version + RESET,
                GRAY + agentId + " · " + model + RESET,
                GRAY + projectPath + RESET,
                GRAY + agentCount + " agent · " + skillCount + " skill · "
                        + mcpCount + " mcp · " + taskCount + " task" + RESET,
                ""  // feet 行沒有 info
        };

        // 計算 mascot 最大寬度
        int mascotMaxWidth = 0;
        for (String line : mascotPlain) {
            mascotMaxWidth = Math.max(mascotMaxWidth, DisplayWidth.of(line));
        }

        // Layout: [mascot(fixed)] [gap] [info(fill)]
        int gap = 2;
        int[] widths = Layout.horizontal(cols, gap,
                new Layout.Fixed(mascotMaxWidth),
                new Layout.Fill());
        int mascotCol = widths[0];

        // 組裝每行：padRight(mascot) + gap + info
        var sb = new StringBuilder();
        for (int i = 0; i < mascotPlain.length; i++) {
            // mascot 部分：用 plain 算 padding，再把 padding 接在 ANSI 版後面
            int mascotLineWidth = DisplayWidth.of(mascotPlain[i]);
            String mascotPad = DisplayWidth.fill(mascotCol - mascotLineWidth);

            sb.append(mascotAnsi[i]);
            sb.append(mascotPad);
            sb.append(DisplayWidth.fill(gap));
            sb.append(info[i]);
            sb.append("\n");
        }
        return sb.toString();
    }
}
