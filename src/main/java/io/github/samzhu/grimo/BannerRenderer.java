package io.github.samzhu.grimo;

import io.github.samzhu.grimo.shared.tui.DisplayWidth;
import io.github.samzhu.grimo.shared.tui.TuiComponent;
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
public class BannerRenderer implements TuiComponent {

    @Override
    public List<AttributedString> render(int width) {
        // TuiComponent 契約：BannerRenderer 需要額外參數，此方法供介面相容
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
     * @param workspacePath 工作目錄路徑
     * @param agentCount    偵測到的可用 Agent 數量
     * @param skillCount    已載入的 Skill 數量
     * @param mcpCount      已連線的 MCP Server 數量
     * @param taskCount     已排程的 Task 數量
     * @param cols          終端寬度（目前保留供未來動態排版使用）
     * @return 含 ANSI 色碼的多行 banner 字串
     */
    public String render(String version, String agentId, String model,
                         String workspacePath, int agentCount, int skillCount, int mcpCount, int taskCount,
                         int cols) {
        // 設計說明：mascot 約佔 16 欄，info 從 mascot 右側開始
        // 用 DisplayWidth.fill() 動態產生間距，預設 gap 8 欄（cols < 60 時仍保底 4 欄）
        int mascotWidth = 16;
        int minGap = 4;
        int dynamicGap = Math.max(minGap, Math.min(8, cols - mascotWidth - 40));
        String gap = DisplayWidth.fill(dynamicGap);
        var sb = new StringBuilder();
        sb.append(GOLD).append("       ✦").append(RESET)
          .append(gap).append("    ")
          .append(WHITE).append("Grimo").append(RESET)
          .append(" ").append(GRAY).append("v").append(version).append(RESET)
          .append("\n");
        sb.append(BRAND).append("     ▄████▄").append(RESET)
          .append(gap)
          .append(GRAY).append(agentId).append(" · ").append(model).append(RESET)
          .append("\n");
        sb.append(BRAND).append("     █").append(WHITE).append("●").append(BRAND).append("██")
          .append(WHITE).append("●").append(BRAND).append("█").append(RESET)
          .append(gap)
          .append(GRAY).append(workspacePath).append(RESET)
          .append("\n");
        sb.append(BRAND).append("     ██████").append(RESET)
          .append(gap)
          .append(GRAY).append(agentCount).append(" agent · ")
          .append(skillCount).append(" skill · ")
          .append(mcpCount).append(" mcp · ")
          .append(taskCount).append(" task").append(RESET)
          .append("\n");
        sb.append(BRAND).append("     ▀▄▀▀▄▀").append(RESET).append("\n");
        return sb.toString();
    }
}
