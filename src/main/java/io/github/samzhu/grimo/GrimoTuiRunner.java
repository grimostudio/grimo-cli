package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.detect.AgentDetector;
import io.github.samzhu.grimo.agent.detect.AgentDetector.DetectionResult;
import io.github.samzhu.grimo.mcp.client.McpClientManager;
import io.github.samzhu.grimo.mcp.client.McpClientRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import io.github.samzhu.grimo.shared.workspace.WorkspaceManager;
import io.github.samzhu.grimo.skill.loader.SkillLoader;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import io.github.samzhu.grimo.task.scheduler.TaskSchedulerService;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.Widget;
import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.shell.core.ShellRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 自建 TUI Runner：取代 Spring Shell 的 InteractiveShellApplicationRunner。
 *
 * 設計說明：
 * - 實作 ApplicationRunner → Spring Shell 的 springShellApplicationRunner 有
 *   {@code @ConditionalOnMissingBean(ApplicationRunner.class)}，當此 bean 存在時
 *   Spring Shell 不會建立自己的 ApplicationRunner wrapper
 * - 啟動流程（動畫、偵測、banner、status line）在此完成
 * - fill-lines 在 readLine() 之前執行，不會與 LineReader 的顯示管理衝突
 * - JLine LineReader options 和 / widget 在 readLine() 之前配置
 * - 最終委派給 JLineShellRunner.run() 執行 REPL loop
 *
 * 為什麼不用 CommandLineRunner？
 * - CommandLineRunner 不會抑制 Spring Shell 的 ApplicationRunner
 * - 舊架構中 Spring Shell 的 REPL 控制 LineReader，與我們的 terminal.writer() 衝突
 * - 新架構中我們完全控制啟動順序：setup → shellRunner.run()
 *
 * @see <a href="https://docs.spring.io/spring-shell/reference/execution.html">Spring Shell Execution</a>
 * @see <a href="https://github.com/jline/jline3/wiki/Completion">JLine 3 Completion</a>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GrimoTuiRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GrimoTuiRunner.class);

    private final ShellRunner shellRunner;
    private final Terminal terminal;
    private final LineReader lineReader;
    private final WorkspaceManager workspaceManager;
    private final GrimoConfig grimoConfig;
    private final AgentDetector agentDetector;
    private final SkillLoader skillLoader;
    private final SkillRegistry skillRegistry;
    private final TaskSchedulerService taskSchedulerService;
    private final McpClientManager mcpClientManager;
    private final McpClientRegistry mcpClientRegistry;
    private final BannerRenderer bannerRenderer;

    public GrimoTuiRunner(ShellRunner shellRunner,
                           Terminal terminal,
                           LineReader lineReader,
                           WorkspaceManager workspaceManager,
                           GrimoConfig grimoConfig,
                           AgentDetector agentDetector,
                           SkillLoader skillLoader,
                           SkillRegistry skillRegistry,
                           TaskSchedulerService taskSchedulerService,
                           McpClientManager mcpClientManager,
                           McpClientRegistry mcpClientRegistry,
                           BannerRenderer bannerRenderer) {
        this.shellRunner = shellRunner;
        this.terminal = terminal;
        this.lineReader = lineReader;
        this.workspaceManager = workspaceManager;
        this.grimoConfig = grimoConfig;
        this.agentDetector = agentDetector;
        this.skillLoader = skillLoader;
        this.skillRegistry = skillRegistry;
        this.taskSchedulerService = taskSchedulerService;
        this.mcpClientManager = mcpClientManager;
        this.mcpClientRegistry = mcpClientRegistry;
        this.bannerRenderer = bannerRenderer;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // === Phase 1: Workspace 初始化 ===
        if (!workspaceManager.isInitialized()) {
            workspaceManager.initialize();
        }

        // === Phase 2: 動畫 ===
        boolean animated = StartupAnimationRenderer.isAnimationSupported(terminal.getType());
        StartupAnimationRenderer animator = animated ? new StartupAnimationRenderer(terminal) : null;

        if (animated) {
            animator.playIntroAnimation();
        }

        // === Phase 3: 同步載入 + 動畫步驟顯示 ===
        List<DetectionResult> agentResults = detectAgents(animated, animator);
        loadSkills(animated, animator);
        connectMcp(animated, animator);
        restoreTasks(animated, animator);

        // === Phase 4: 動畫結束過渡 ===
        if (animated) {
            Thread.sleep(800);
            animator.clearAnimation();
            // 清除 main buffer 殘留，確保 banner 從終端頂部開始完整顯示
            terminal.writer().print("\033[H\033[2J");
            terminal.writer().flush();
        }

        // === Phase 5: 靜態 banner ===
        String version = getClass().getPackage().getImplementationVersion();
        if (version == null) version = "dev";
        String agentId = resolveAgentId(agentResults);
        String model = grimoConfig.getDefaultModel();
        if (model == null) model = "unknown";
        String workspacePath = workspaceManager.root().toString()
                .replace(System.getProperty("user.home"), "~");
        long agentCount = agentResults.stream().filter(DetectionResult::available).count();

        terminal.writer().print(bannerRenderer.render(
                version, agentId, model, workspacePath,
                (int) agentCount,
                skillRegistry.listAll().size(),
                mcpClientRegistry.listAll().size(),
                taskSchedulerService.getScheduledTaskIds().size()));
        terminal.writer().println();
        terminal.writer().flush();

        // === Phase 6: 手動 ANSI scroll region + 底部固定欄 ===
        // 不使用 JLine Status API（其 scroll region 與手動佈局衝突）。
        // 手動設定 scroll region 排除底部 2 行，在 scroll region 外繪製分隔線 + 狀態。
        // LineReader 在 scroll region 內操作，不會覆蓋底部欄。
        int h = terminal.getHeight();
        int w = terminal.getWidth();
        String sep = "─".repeat(w);
        int skillCount = skillRegistry.listAll().size();
        int mcpCount = mcpClientRegistry.listAll().size();
        int taskCount = taskSchedulerService.getScheduledTaskIds().size();
        String statusText = " " + agentId + " · " + model + " │ " + workspacePath
                + " │ " + (int) agentCount + " agent · " + skillCount + " skill · "
                + mcpCount + " mcp · " + taskCount + " task";

        // 1. 設定 scroll region：rows 1 to H-2（排除底部 2 行）
        terminal.writer().printf("\033[1;%dr", h - 2);
        // 2. 在 scroll region 外繪製底部欄
        terminal.writer().printf("\033[%d;1H\033[38;5;245m%s\033[0m", h - 1, sep);
        terminal.writer().printf("\033[%d;1H\033[38;5;37m%s\033[0m", h, statusText);
        // 3. 游標回到 scroll region 底部（readLine 從這裡開始）
        terminal.writer().printf("\033[%d;1H", h - 2);
        terminal.writer().flush();

        // === Phase 8: 配置 JLine LineReader ===
        configureLineReader();
        registerSlashWidget();

        log.debug("Grimo TUI setup complete, delegating to ShellRunner REPL.");

        // === Phase 9: 委派給 JLineShellRunner 執行 REPL loop ===
        // ShellRunner.run() 內部呼叫 JLineInputProvider.readInput() → lineReader.readLine(prompt)
        // 所有 readLine() 呼叫都在此之後，fill-lines 和 LineReader 狀態不會衝突
        shellRunner.run(args.getSourceArgs());
    }

    /**
     * 配置 JLine LineReader 補全選項和選單樣式。
     *
     * 設計說明：
     * - AUTO_MENU_LIST + AUTO_LIST：輸入 / 後自動顯示候選清單
     * - MENU_LIST_MAX=5：限制顯示 5 項，仿 Claude Code 的簡潔選單
     * - 樣式配置去除 JLine 預設的洋紅色背景，改為透明底 + cyan/gray 配色
     * - 選中項使用 inverse（反色）高亮
     *
     * @see <a href="https://github.com/jline/jline3/wiki/Completion">JLine Completion Options</a>
     * @see <a href="https://github.com/jline/jline3/wiki/Theme-System">JLine Theme System</a>
     */
    private void configureLineReader() {
        // 補全行為
        lineReader.setOpt(LineReader.Option.AUTO_MENU_LIST);
        lineReader.setOpt(LineReader.Option.AUTO_LIST);
        lineReader.setOpt(LineReader.Option.LIST_ROWS_FIRST);

        // 選單樣式：透明底 + cyan/gray 配色（取代預設的醜陋洋紅色）
        lineReader.setVariable(LineReader.COMPLETION_STYLE_LIST_BACKGROUND, "bg:default");
        lineReader.setVariable(LineReader.COMPLETION_STYLE_STARTING, "fg:cyan");
        lineReader.setVariable(LineReader.COMPLETION_STYLE_DESCRIPTION, "fg:bright-black");
        lineReader.setVariable(LineReader.COMPLETION_STYLE_SELECTION, "fg:cyan,bold,inverse");
        lineReader.setVariable(LineReader.COMPLETION_STYLE_GROUP, "fg:cyan,bold");

        // 限制選單最多顯示 5 項，不佔滿螢幕
        lineReader.setVariable(LineReader.MENU_LIST_MAX, 5);
    }

    /**
     * 註冊 / 鍵 widget：游標在行首時輸入 / 並觸發 JLine 內建補全。
     * 使用 LineReader.callWidget(COMPLETE) 觸發補全，由 JLine 自己管理選單渲染，
     * 不會破壞 LineReader 的內部狀態（解決舊 SlashMenuRenderer 的根本問題）。
     *
     * @see <a href="https://github.com/jline/jline3/wiki/Using-line-readers">JLine Widgets</a>
     */
    private void registerSlashWidget() {
        Widget slashAndComplete = () -> {
            if (lineReader.getBuffer().cursor() == 0) {
                lineReader.getBuffer().write('/');
                lineReader.callWidget(LineReader.MENU_COMPLETE);
            } else {
                lineReader.getBuffer().write('/');
            }
            return true;
        };
        lineReader.getWidgets().put("slash-complete", slashAndComplete);
        lineReader.getKeyMaps().get(LineReader.MAIN)
                .bind(new Reference("slash-complete"), "/");
    }

    // === 載入步驟方法 ===

    private List<DetectionResult> detectAgents(boolean animated, StartupAnimationRenderer animator) {
        String detail;
        boolean success;
        List<DetectionResult> results = List.of();
        try {
            results = agentDetector.detect();
            long available = results.stream().filter(DetectionResult::available).count();
            detail = available + " available";
            success = true;
        } catch (Exception e) {
            detail = e.getMessage();
            success = false;
        }
        showStep(animated, animator, 0, "Detecting agents", detail, success);
        return results;
    }

    private void loadSkills(boolean animated, StartupAnimationRenderer animator) {
        String detail;
        boolean success;
        try {
            var skills = skillLoader.loadAll();
            skills.forEach(skillRegistry::register);
            detail = skills.size() + " loaded";
            success = true;
        } catch (Exception e) {
            detail = e.getMessage();
            success = false;
        }
        showStep(animated, animator, 1, "Loading skills", detail, success);
    }

    @SuppressWarnings("unchecked")
    private void connectMcp(boolean animated, StartupAnimationRenderer animator) {
        String detail;
        boolean success;
        try {
            var config = grimoConfig.load();
            int count = 0;
            if (config.containsKey("mcp")) {
                var mcpServers = (Map<String, Map<String, String>>) config.get("mcp");
                for (var entry : mcpServers.entrySet()) {
                    try {
                        String transport = entry.getValue().get("transport");
                        String command = entry.getValue().get("command");
                        if ("stdio".equals(transport)) {
                            mcpClientManager.addStdio(entry.getKey(), command);
                            count++;
                        }
                    } catch (Exception e) {
                        // 個別 MCP server 連線失敗不中斷流程
                    }
                }
            }
            detail = count + " servers";
            success = true;
        } catch (Exception e) {
            detail = e.getMessage();
            success = false;
        }
        showStep(animated, animator, 2, "Connecting MCP", detail, success);
    }

    private void restoreTasks(boolean animated, StartupAnimationRenderer animator) {
        String detail;
        boolean success;
        try {
            taskSchedulerService.restoreAll();
            int count = taskSchedulerService.getScheduledTaskIds().size();
            detail = count + " active";
            success = true;
        } catch (Exception e) {
            detail = e.getMessage();
            success = false;
        }
        showStep(animated, animator, 3, "Restoring tasks", detail, success);
    }

    private void showStep(boolean animated, StartupAnimationRenderer animator,
                           int index, String label, String detail, boolean success) {
        String text = StartupAnimationRenderer.formatLoadingStep(label, detail, success);
        if (animated) {
            animator.showLoadingStep(index, text);
        } else {
            terminal.writer().println(text);
            terminal.writer().flush();
        }
    }

    private String resolveAgentId(List<DetectionResult> agentResults) {
        String agentId = grimoConfig.getDefaultAgent();
        if (agentId == null) {
            agentId = agentResults.stream()
                    .filter(DetectionResult::available)
                    .map(DetectionResult::id)
                    .findFirst().orElse("no agent");
        }
        return agentId;
    }
}
