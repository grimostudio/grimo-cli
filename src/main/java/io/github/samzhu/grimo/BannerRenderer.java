package io.github.samzhu.grimo;

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
public class BannerRenderer {

    // ANSI 256 色碼
    private static final String CYAN = "\033[38;5;37m";     // 青色（品牌色 #5ba3b5）
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
     * @param skillCount    已載入的 Skill 數量
     * @param mcpCount      已連線的 MCP Server 數量
     * @param taskCount     已排程的 Task 數量
     * @return 含 ANSI 色碼的多行 banner 字串
     */
    public String render(String version, String agentId, String model,
                         String workspacePath, int skillCount, int mcpCount, int taskCount) {
        String gap = "        ";  // 8 spaces between mascot and info
        var sb = new StringBuilder();
        sb.append(GOLD).append("    ✦").append(RESET)
          .append(gap).append("       ")
          .append(WHITE).append("Grimo").append(RESET)
          .append(" ").append(GRAY).append("v").append(version).append(RESET)
          .append("\n");
        sb.append(CYAN).append("  ▄████▄").append(RESET)
          .append(gap)
          .append(GRAY).append(agentId).append(" · ").append(model).append(RESET)
          .append("\n");
        sb.append(CYAN).append("  █").append(WHITE).append("●").append(CYAN).append("██")
          .append(WHITE).append("●").append(CYAN).append("█").append(RESET)
          .append(gap)
          .append(GRAY).append(workspacePath).append(RESET)
          .append("\n");
        sb.append(CYAN).append("  ██████").append(RESET)
          .append(gap)
          .append(GRAY).append(skillCount).append(" skills · ")
          .append(mcpCount).append(" mcp · ")
          .append(taskCount).append(" task").append(RESET)
          .append("\n");
        sb.append(CYAN).append("  ▀▄▀▀▄▀").append(RESET).append("\n");
        return sb.toString();
    }
}
