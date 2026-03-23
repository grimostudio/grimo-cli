package io.github.samzhu.grimo;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

import java.util.List;

/**
 * 封裝 JLine Status API，在終端底部顯示持久狀態列。
 *
 * 設計說明：
 * - 使用 Status.getStatus(terminal) 取得或建立 per-terminal 單例（內部存在 terminal attributes 中）
 * - null terminal 或不支援 Status 的終端（dumb/null）下所有操作為 no-op
 * - buildStatusLine() 是純函數，方便單元測試
 * - update() 加 synchronized 避免多執行緒同時更新造成終端輸出錯亂
 * - suspend()/restore() 供 SlashMenuRenderer 在選單開啟/關閉時暫停/恢復狀態列
 *
 * 格式：provider · model │ workspace │ N agent · N skill · N mcp · N task
 *
 * @see <a href="https://github.com/jline/jline3/blob/master/terminal/src/main/java/org/jline/utils/Status.java">JLine Status.java</a>
 */
public class StatusLineRenderer {

    private static final AttributedStyle CYAN = AttributedStyle.DEFAULT.foreground(37);
    private static final AttributedStyle GRAY = AttributedStyle.DEFAULT.foreground(245);
    private static final AttributedStyle WHITE_BOLD = AttributedStyle.BOLD.foreground(AttributedStyle.WHITE);

    private final Status status;

    /** 儲存最近一次的參數，供 restore() 後重新繪製 */
    private volatile String lastProvider, lastModel, lastWorkspace;
    private volatile int lastAgentCount, lastSkillCount, lastMcpCount, lastTaskCount;

    public StatusLineRenderer(Terminal terminal) {
        this.status = (terminal != null) ? Status.getStatus(terminal) : null;
    }

    /**
     * 純函數：組裝狀態列 AttributedString。
     * 格式：provider · model │ workspace │ N agent · N skill · N mcp · N task
     */
    public AttributedString buildStatusLine(String provider, String model,
                                             String workspacePath,
                                             int agentCount, int skillCount,
                                             int mcpCount, int taskCount) {
        var sb = new AttributedStringBuilder();
        sb.append(" ");
        sb.styled(CYAN, provider);
        sb.styled(GRAY, " \u00b7 ");
        sb.styled(CYAN, model);
        sb.styled(GRAY, " \u2502 ");
        sb.styled(GRAY, workspacePath);
        sb.styled(GRAY, " \u2502 ");
        sb.styled(WHITE_BOLD, String.valueOf(agentCount));
        sb.styled(GRAY, " agent \u00b7 ");
        sb.styled(WHITE_BOLD, String.valueOf(skillCount));
        sb.styled(GRAY, " skill \u00b7 ");
        sb.styled(WHITE_BOLD, String.valueOf(mcpCount));
        sb.styled(GRAY, " mcp \u00b7 ");
        sb.styled(WHITE_BOLD, String.valueOf(taskCount));
        sb.styled(GRAY, " task");
        return sb.toAttributedString();
    }

    /**
     * 更新終端底部狀態列。synchronized 避免多執行緒同時呼叫。
     * terminal 為 null 或不支援 Status 時為 no-op。
     */
    public synchronized void update(String provider, String model, String workspacePath,
                                     int agentCount, int skillCount, int mcpCount, int taskCount) {
        this.lastProvider = provider;
        this.lastModel = model;
        this.lastWorkspace = workspacePath;
        this.lastAgentCount = agentCount;
        this.lastSkillCount = skillCount;
        this.lastMcpCount = mcpCount;
        this.lastTaskCount = taskCount;
        if (status != null) {
            status.update(List.of(buildStatusLine(provider, model, workspacePath,
                    agentCount, skillCount, mcpCount, taskCount)));
        }
    }

    /** 暫停狀態列顯示（選單開啟時呼叫）。 */
    public synchronized void suspend() {
        if (status != null) {
            status.suspend();
        }
    }

    /** 恢復狀態列顯示（選單關閉時呼叫），使用最近一次的參數重新繪製。 */
    public synchronized void restore() {
        if (status != null) {
            status.restore();
            if (lastProvider != null) {
                status.update(List.of(buildStatusLine(lastProvider, lastModel, lastWorkspace,
                        lastAgentCount, lastSkillCount, lastMcpCount, lastTaskCount)));
            }
        }
    }
}
