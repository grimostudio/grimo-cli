package io.github.samzhu.grimo.tui;

import io.github.samzhu.grimo.agent.AgentCommands;
import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.config.GrimoConfig;
import io.github.samzhu.grimo.project.ProjectContext;
import io.github.samzhu.grimo.shared.event.AgentSwitchedEvent;
import io.github.samzhu.grimo.shared.event.DevModeCompletedEvent;
import io.github.samzhu.grimo.shared.event.DevModeEnteredEvent;
import io.github.samzhu.grimo.shared.event.McpCatalogChangedEvent;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import io.github.samzhu.grimo.task.scheduler.TaskSchedulerService;
import io.github.samzhu.grimo.tui.view.ContentView;
import io.github.samzhu.grimo.tui.view.StatusView;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * TUI event bridge：將 domain events 轉為 TUI 更新。
 *
 * 設計說明：
 * - 從 TuiAdapter 提取 @EventListener 方法，職責單一化（SP4 Task 3）
 * - TUI 元件（StatusView、ContentView）不是 Spring bean，由 TuiAdapter.run() 建構後
 *   透過 bind() 注入，避免循環依賴
 * - Spring beans（GrimoConfig、AgentModelRegistry 等）由 constructor injection 注入
 * - listener 收到 event 時若 TUI 尚未初始化（bind 尚未呼叫），直接 return 防衛
 */
@Component
public class TuiEventBridge {

    private final GrimoConfig grimoConfig;
    private final AgentModelRegistry agentModelRegistry;
    private final SkillRegistry skillRegistry;
    private final TaskSchedulerService taskSchedulerService;
    private final ProjectContext projectContext;

    /** TUI 元件（run 時由 TuiAdapter.bind() 設定） */
    private volatile StatusView statusView;
    private volatile ContentView contentView;
    private volatile Runnable setDirty;

    /** originalStatusText 同步：bind 後初始值由呼叫方傳入，refreshStatusBar 每次重建並更新 */
    private volatile String originalStatusText;

    public TuiEventBridge(GrimoConfig grimoConfig,
                          AgentModelRegistry agentModelRegistry,
                          SkillRegistry skillRegistry,
                          TaskSchedulerService taskSchedulerService,
                          ProjectContext projectContext) {
        this.grimoConfig = grimoConfig;
        this.agentModelRegistry = agentModelRegistry;
        this.skillRegistry = skillRegistry;
        this.taskSchedulerService = taskSchedulerService;
        this.projectContext = projectContext;
    }

    /**
     * TuiAdapter 在 run() 建構 TUI 元件後呼叫此方法完成繫結。
     * TUI 元件不是 Spring bean，必須在 run() 後才能取得。
     *
     * @param statusView        status bar 元件
     * @param contentView       主內容區元件
     * @param setDirty          觸發重繪的回呼（通常是 eventLoop::setDirty）
     * @param originalStatusText 初始 status text（由 TuiAdapter 建構時產生）
     */
    public void bind(StatusView statusView,
                     ContentView contentView,
                     Runnable setDirty,
                     String originalStatusText) {
        this.statusView = statusView;
        this.contentView = contentView;
        this.setDirty = setDirty;
        this.originalStatusText = originalStatusText;
    }

    /**
     * 取得最新的 originalStatusText（agent thread finally 區塊需要恢復 status bar 用）。
     */
    public String getOriginalStatusText() {
        return originalStatusText;
    }

    /**
     * 設計說明：Command → Event → TUI 解耦
     * AgentCommands.use() publish event → 這裡自動刷新 status bar
     */
    @EventListener
    void on(AgentSwitchedEvent event) {
        if (statusView == null) return; // TUI 尚未初始化
        refreshStatusBar();
        setDirty.run();
    }

    @EventListener
    void on(McpCatalogChangedEvent event) {
        if (statusView == null) return;
        refreshStatusBar();
        setDirty.run();
    }

    @EventListener
    void on(DevModeEnteredEvent event) {
        if (contentView == null) return;

        var wtLine = new AttributedStringBuilder();
        wtLine.styled(AttributedStyle.DEFAULT.foreground(2), "\u26a1 ");
        wtLine.append("Dev Mode (" + event.branchName() + ")");
        contentView.appendLine(wtLine.toAttributedString());

        var statusLine = new AttributedStringBuilder();
        statusLine.styled(AttributedStyle.DEFAULT.foreground(245),
                "  \u2514 Worktree created, agent working with full access...");
        contentView.appendLine(statusLine.toAttributedString());

        setDirty.run();
    }

    @EventListener
    void on(DevModeCompletedEvent event) {
        if (contentView == null) return;

        float seconds = event.durationMs() / 1000f;

        if (event.hasChanges()) {
            var headerLine = new AttributedStringBuilder();
            headerLine.styled(AttributedStyle.DEFAULT.foreground(2),
                    "\u23fa Dev Mode completed (" + String.format("%.0fs", seconds) + ")");
            contentView.appendLine(headerLine.toAttributedString());

            var branchLine = new AttributedStringBuilder();
            branchLine.styled(AttributedStyle.DEFAULT.foreground(245),
                    "  Branch: " + event.branchName());
            contentView.appendLine(branchLine.toAttributedString());

            if (!event.diffStat().isBlank()) {
                for (String line : event.diffStat().split("\n")) {
                    var diffLine = new AttributedStringBuilder();
                    diffLine.styled(AttributedStyle.DEFAULT.foreground(245),
                            "  " + line.trim());
                    contentView.appendLine(diffLine.toAttributedString());
                }
            }

            var commitLine = new AttributedStringBuilder();
            commitLine.styled(AttributedStyle.DEFAULT.foreground(245),
                    "  Commits: " + event.commitCount());
            contentView.appendLine(commitLine.toAttributedString());

            var mergeLine = new AttributedStringBuilder();
            mergeLine.styled(AttributedStyle.DEFAULT.foreground(67),
                    "  \u2192 git merge " + event.branchName());
            contentView.appendLine(mergeLine.toAttributedString());
        } else {
            var line = new AttributedStringBuilder();
            line.styled(AttributedStyle.DEFAULT.foreground(245),
                    "\u23fa Dev Mode completed (" + String.format("%.0fs", seconds) + ") \u2014 no file changes");
            contentView.appendLine(line.toAttributedString());
        }

        if (event.result() != null && !event.result().isBlank()) {
            contentView.appendAiReply(event.result());
        }

        contentView.appendLine(AttributedString.EMPTY);
        setDirty.run();
    }

    /**
     * 從 config 重新讀取 agent/model，更新 status bar。
     * /agent-use 等指令觸發 event 後自動呼叫，確保即時反映切換結果。
     */
    private void refreshStatusBar() {
        String agentId = grimoConfig.getDefaultAgent();
        if (agentId == null) agentId = "no agent";
        String model = grimoConfig.getAgentOption(agentId, "model");
        if (model == null) model = grimoConfig.getDefaultModel();
        if (model == null) model = AgentCommands.RECOMMENDED_MODELS.getOrDefault(agentId, "unknown");

        String projectPath = projectContext.displayPath();
        long agentCount = agentModelRegistry.listAll().values().stream()
                .filter(m -> m.isAvailable()).count();
        int skillCount = skillRegistry.listAll().size();
        int mcpCount = grimoConfig.getMcpServers().size();
        int taskCount = taskSchedulerService.getScheduledTaskIds().size();

        String newStatus = agentId + " · " + model + " │ " + projectPath
                + " │ " + (int) agentCount + " agent · " + skillCount + " skill · "
                + mcpCount + " mcp · " + taskCount + " task";
        this.originalStatusText = newStatus;
        statusView.setStatusText(newStatus);
    }
}
