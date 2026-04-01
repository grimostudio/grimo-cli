package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.AgentCommands;
import io.github.samzhu.grimo.agent.detect.AgentModelFactory;
import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.router.AgentRouter;
import io.github.samzhu.grimo.agent.tier.Tier;
import io.github.samzhu.grimo.agent.tier.TierKeywordDetector;
import io.github.samzhu.grimo.agent.tier.TierOptionsFactory;
import io.github.samzhu.grimo.agent.tier.TierRouter;
import io.github.samzhu.grimo.agent.tier.TierSelection;
import io.github.samzhu.grimo.mcp.McpCatalogBuilder;
import io.github.samzhu.grimo.shared.sandbox.GitHelper;
import io.github.samzhu.grimo.shared.sandbox.WorktreeProvisioner;
import io.github.samzhu.grimo.shared.sandbox.WorktreeInfo;
import io.github.samzhu.grimo.shared.sandbox.SandboxDetector;
import io.github.samzhu.grimo.config.GrimoConfig;
import io.github.samzhu.grimo.home.GrimoHome;
import io.github.samzhu.grimo.project.ProjectContext;
import io.github.samzhu.grimo.skill.loader.SkillLoader;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import io.github.samzhu.grimo.shared.session.SessionWriter;
import io.github.samzhu.grimo.task.scheduler.TaskSchedulerService;
import io.github.samzhu.grimo.tui.TuiEventBridge;
import org.springaicommunity.agents.client.AgentClient;
import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.CommandExecutor;
import org.springframework.shell.core.command.CommandParser;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.stereotype.Component;

import io.github.samzhu.grimo.tui.TuiKeyHandler;
import io.github.samzhu.grimo.tui.overlay.McpPanel;
import io.github.samzhu.grimo.tui.overlay.SlashMenu;
import io.github.samzhu.grimo.tui.screen.EventLoop;
import io.github.samzhu.grimo.tui.screen.Screen;
import io.github.samzhu.grimo.tui.selection.AutoScroller;
import io.github.samzhu.grimo.tui.selection.Clipboard;
import io.github.samzhu.grimo.tui.selection.TextSelection;
import io.github.samzhu.grimo.tui.view.ContentView;
import io.github.samzhu.grimo.tui.view.InputView;
import io.github.samzhu.grimo.tui.view.StatusView;
import io.github.samzhu.grimo.tui.widget.Banner;
import io.github.samzhu.grimo.tui.widget.GroupedSelect;
import io.github.samzhu.grimo.tui.widget.ListSelect;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 自建 TUI Runner：使用 JLine Display + BindingReader 建構完整 TUI 介面。
 *
 * 設計說明：
 * - 取代 Spring Shell TerminalUI（其 mouse event parsing 有 KeyMap binding bug）
 * - 使用 JLine Display 做 diff-based 渲染（不閃爍）
 * - 使用 JLine BindingReader + KeyMap 正確處理 SGR mouse events（支援滾輪捲動）
 * - 雙執行緒模式（參考 JLine Tmux.java）：input thread + render thread
 * - 保留 Spring Shell CommandParser/CommandExecutor/CommandRegistry 做命令處理
 *
 * @see <a href="https://jline.org/docs/advanced/mouse-support/">JLine Mouse Support</a>
 * @see <a href="https://github.com/jline/jline3/blob/master/terminal/src/main/java/org/jline/utils/Display.java">JLine Display</a>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GrimoTuiRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GrimoTuiRunner.class);

    private final Terminal terminal;
    private final GrimoHome grimoHome;
    private final ProjectContext projectContext;
    private final GrimoConfig grimoConfig;
    private final AgentModelFactory agentModelFactory;
    private final AgentModelRegistry agentModelRegistry;
    private final AgentRouter agentRouter;
    private final SkillLoader skillLoader;
    private final SkillRegistry skillRegistry;
    private final TaskSchedulerService taskSchedulerService;
    private final Banner banner;
    private final CommandParser commandParser;
    private final CommandExecutor commandExecutor;
    private final CommandRegistry commandRegistry;
    private final McpCatalogBuilder mcpCatalogBuilder;
    private final WorktreeProvisioner worktreeProvisioner;
    private final GitHelper gitHelper;
    private final SandboxDetector sandboxDetector;
    private final TierRouter tierRouter;
    private final TierKeywordDetector tierKeywordDetector;
    private final TierOptionsFactory tierOptionsFactory;
    private final AtomicReference<Tier> sessionTier;
    private final TuiEventBridge tuiEventBridge;

    /** 本輪 tier（顯示用）：每次 processInput 重設 */
    private volatile TierSelection currentTierSelection;

    /** Status bar 元件（run 時初始化，需在 agent thread 中更新） */
    private StatusView statusView;

    /**
     * AI 對話併發控制：封裝在 AgentStateRef，供 TuiKeyHandler 在 Ctrl+C 時中斷 agent。
     * 設計說明：agentRunning / agentThread 同時被 processInput（GrimoTuiRunner）和
     * handleNormalKey（TuiKeyHandler）讀寫，透過共用 reference 保持一致。
     */
    private final TuiKeyHandler.AgentStateRef agentState = new TuiKeyHandler.AgentStateRef();

    // TUI 元件（run 時初始化）
    private ContentView contentView;
    private InputView inputView;
    private SlashMenu slashMenu;
    private McpPanel mcpPanel;
    private Screen screen;
    private EventLoop eventLoop;
    private final SessionWriter sessionWriter;
    private TextSelection textSelection;
    private AutoScroller autoScroller;
    private Clipboard clipboard;

    /** 鍵盤/滑鼠事件處理器（run 時初始化） */
    private TuiKeyHandler tuiKeyHandler;

    public GrimoTuiRunner(Terminal terminal,
                           GrimoHome grimoHome,
                           ProjectContext projectContext,
                           SessionWriter sessionWriter,
                           GrimoConfig grimoConfig,
                           AgentModelFactory agentModelFactory,
                           AgentModelRegistry agentModelRegistry,
                           AgentRouter agentRouter,
                           SkillLoader skillLoader,
                           SkillRegistry skillRegistry,
                           TaskSchedulerService taskSchedulerService,
                           Banner banner,
                           CommandParser commandParser,
                           CommandExecutor commandExecutor,
                           CommandRegistry commandRegistry,
                           McpCatalogBuilder mcpCatalogBuilder,
                           WorktreeProvisioner worktreeProvisioner,
                           GitHelper gitHelper,
                           SandboxDetector sandboxDetector,
                           TierRouter tierRouter,
                           TierKeywordDetector tierKeywordDetector,
                           TierOptionsFactory tierOptionsFactory,
                           AtomicReference<Tier> sessionTier,
                           TuiEventBridge tuiEventBridge) {
        this.terminal = terminal;
        this.grimoHome = grimoHome;
        this.projectContext = projectContext;
        this.sessionWriter = sessionWriter;
        this.grimoConfig = grimoConfig;
        this.agentModelFactory = agentModelFactory;
        this.agentModelRegistry = agentModelRegistry;
        this.agentRouter = agentRouter;
        this.skillLoader = skillLoader;
        this.skillRegistry = skillRegistry;
        this.taskSchedulerService = taskSchedulerService;
        this.banner = banner;
        this.commandParser = commandParser;
        this.commandExecutor = commandExecutor;
        this.commandRegistry = commandRegistry;
        this.mcpCatalogBuilder = mcpCatalogBuilder;
        this.worktreeProvisioner = worktreeProvisioner;
        this.gitHelper = gitHelper;
        this.sandboxDetector = sandboxDetector;
        this.tierRouter = tierRouter;
        this.tierKeywordDetector = tierKeywordDetector;
        this.tierOptionsFactory = tierOptionsFactory;
        this.sessionTier = sessionTier;
        this.tuiEventBridge = tuiEventBridge;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // === Phase 1: Home & Project 初始化 ===
        if (!grimoHome.isInitialized()) {
            grimoHome.initialize();
        }
        projectContext.initialize();

        // === Phase 2: 同步載入（靜默，無動畫）===
        var agentResults = agentModelFactory.detectAndRegister(
                java.nio.file.Path.of(System.getProperty("user.dir")));
        loadSkills();

        // Phase 2: MCP catalog 初始建構（快取在 McpCatalogBuilder 內，/mcp-add 時自動 rebuild）
        mcpCatalogBuilder.rebuild();
        log.debug("MCP catalog built: {} servers [{}]",
                mcpCatalogBuilder.getServerNames().size(),
                String.join(", ", mcpCatalogBuilder.getServerNames()));

        restoreTasks();

        // Phase 2: Sandbox 後端偵測
        var sandboxResult = sandboxDetector.detect();
        String sandboxMode = sandboxDetector.resolveMode(sandboxResult, grimoConfig.getSandboxMode());
        log.info("Using sandbox mode: {}", sandboxMode);

        // === Phase 3: 準備環境資訊 ===
        String version = getClass().getPackage().getImplementationVersion();
        if (version == null) version = "dev";
        String agentId = resolveAgentId(agentResults);
        String model = grimoConfig.getAgentOption(agentId, "model");
        if (model == null) model = grimoConfig.getDefaultModel();
        if (model == null) model = AgentCommands.RECOMMENDED_MODELS.getOrDefault(agentId, "unknown");
        String projectPath = projectContext.displayPath();
        long agentCount = agentResults.stream()
                .filter(AgentModelFactory.DetectionResult::available).count();
        int skillCount = skillRegistry.listAll().size();
        int mcpCount = grimoConfig.getMcpServers().size();
        int taskCount = taskSchedulerService.getScheduledTaskIds().size();

        // === Phase 4: 建構 TUI 元件 ===
        contentView = new ContentView();
        String bannerText = banner.render(
                version, agentId, model, projectPath,
                (int) agentCount, skillCount, mcpCount, taskCount,
                terminal.getWidth());
        contentView.setBannerText(bannerText);

        inputView = new InputView();

        String statusText = agentId + " · " + model + " │ " + projectPath
                + " │ " + (int) agentCount + " agent · " + skillCount + " skill · "
                + mcpCount + " mcp · " + taskCount + " task";
        this.statusView = new StatusView(statusText);

        var menuItems = buildMenuItems();
        slashMenu = new SlashMenu(menuItems);

        mcpPanel = new McpPanel();
        textSelection = new TextSelection();
        clipboard = new Clipboard();
        screen = new Screen(terminal, contentView, inputView, statusView,
                slashMenu, mcpPanel, textSelection);

        // === Session 對話存檔 ===
        sessionWriter.writeSystemMessage(
            projectContext.path().toString(), version, "Grimo TUI session");

        // === Phase 5: 啟動 TUI 事件迴圈（阻塞） ===
        log.debug("Grimo TUI setup complete, starting raw JLine event loop.");
        // 建構順序說明：
        //   1. TuiKeyHandler 先建立（autoScroller/eventLoop 稍後透過 setter 注入）
        //   2. EventLoop 以 TuiKeyHandler 建立（打破循環依賴）
        //   3. AutoScroller 以 EventLoop 建立，再注入 TuiKeyHandler
        List<String> history = new ArrayList<>();
        tuiKeyHandler = new TuiKeyHandler(
                terminal, screen, contentView, inputView, statusView,
                slashMenu, mcpPanel, textSelection, clipboard,
                grimoConfig, mcpCatalogBuilder, sessionWriter,
                commandParser, commandExecutor, commandRegistry,
                this::processInput,
                history, 0, "",
                agentState);
        eventLoop = new EventLoop(terminal, screen, tuiKeyHandler);
        tuiKeyHandler.setEventLoop(eventLoop);

        autoScroller = new AutoScroller(
            () -> contentView.scrollUp(1),
            () -> contentView.scrollDown(1),
            delta -> textSelection.dragTo(
                    textSelection.getCursorRow() + delta,
                    textSelection.getCursorCol()),
            () -> eventLoop.setDirty()
        );
        tuiKeyHandler.setAutoScroller(autoScroller);

        eventLoop.setOnResize(() -> {
            textSelection.cancel();
            autoScroller.stop();
        });

        // StatusView 的暫時訊息消失後需要觸發重繪
        statusView.setDirtyCallback(() -> eventLoop.setDirty());

        // 繫結 TUI 元件到 TuiEventBridge（domain events → TUI 更新）
        tuiEventBridge.bind(statusView, contentView, () -> eventLoop.setDirty(), statusText);

        eventLoop.run();

        // 事件迴圈結束（/exit），終止 Spring Boot JVM
        System.exit(0);
    }

    /**
     * 顯示 agent → model 互動選擇器（GroupedSelect overlay）。
     * /agent-use 無參數時觸發。
     * 設計說明：showAgentPicker 是業務邏輯（需要 agentModelRegistry），保留在此。
     * overlay 的 activeSelectOverlay 狀態已移至 TuiKeyHandler，透過 setActiveSelectOverlay 注入。
     */
    private void showAgentPicker() {
        var availableAgents = agentModelRegistry.listAvailable();
        if (availableAgents.isEmpty()) {
            contentView.appendLine(new org.jline.utils.AttributedString("No agents available."));
            eventLoop.setDirty();
            return;
        }
        var groups = availableAgents.entrySet().stream()
            .map(e -> {
                String agentId = e.getKey();
                var modelItems = buildModelItems(agentId);
                return new GroupedSelect.Group<>(agentId, modelItems);
            })
            .toList();
        var select = new GroupedSelect<String>(groups, 7);
        tuiKeyHandler.setActiveSelectOverlay(select);
        screen.setSelectOverlay(select);
        eventLoop.setDirty();
    }

    private List<ListSelect.Item<String>> buildModelItems(String agentId) {
        var items = new java.util.ArrayList<ListSelect.Item<String>>();
        String recommended = AgentCommands.RECOMMENDED_MODELS.get(agentId);
        if (recommended != null) {
            items.add(new ListSelect.Item<>(recommended, "推薦", agentId + " " + recommended));
        }
        String remembered = grimoConfig.getAgentOption(agentId, "model");
        if (remembered != null && !remembered.equals(recommended)) {
            items.add(new ListSelect.Item<>(remembered, "上次使用", agentId + " " + remembered));
        }
        // If no items found, add a default entry
        if (items.isEmpty()) {
            items.add(new ListSelect.Item<>(agentId, "default", agentId));
        }
        return items;
    }

    /**
     * 從 CommandRegistry + SkillRegistry 建構斜線指令選單項目。
     */
    private List<SlashMenu.MenuItem> buildMenuItems() {
        var items = new java.util.ArrayList<SlashMenu.MenuItem>();

        commandRegistry.getCommandsByPrefix("").stream()
                .filter(cmd -> !"chat".equals(cmd.getName()))
                .forEach(cmd -> items.add(new SlashMenu.MenuItem(
                        cmd.getName(), cmd.getDescription())));

        skillRegistry.listAll().forEach(skill ->
                items.add(new SlashMenu.MenuItem(
                        skill.name(), skill.description())));

        return items;
    }

    /**
     * 處理使用者輸入：判斷是斜線指令還是對話。
     */
    private void processInput(String text) {
        if (text.equals("/exit")) {
            eventLoop.stop();
            return;
        }

        // /mcp 無子指令時開啟互動 overlay（不走 CommandExecutor）
        if (text.equals("/mcp")) {
            tuiKeyHandler.openMcpManager();
            return;
        }

        // /agent-use 無參數時開啟互動選擇器（不走 CommandExecutor，避免顯示 Usage 訊息）
        if (text.equals("/agent-use")) {
            showAgentPicker();
            return;
        }

        if (text.startsWith("/")) {
            try {
                var parsed = commandParser.parse(text);
                var stringWriter = new StringWriter();
                var printWriter = new PrintWriter(stringWriter);
                var ctx = new CommandContext(parsed, commandRegistry, printWriter, null);
                commandExecutor.execute(ctx);
                printWriter.flush();
                String output = stringWriter.toString().trim();
                if (!output.isEmpty()) {
                    contentView.appendCommandOutput(output);
                    sessionWriter.writeCommandMessage(text, output);
                }
            } catch (Exception e) {
                String errorMsg = "Error: " + e.getMessage();
                contentView.appendError(errorMsg);
                sessionWriter.writeCommandMessage(text, errorMsg);
            }
        } else {
            // AI 對話 — 透過 AgentClient 呼叫 CLI agent
            if (agentState.agentRunning) {
                contentView.appendError("Agent is still running. Wait or press Ctrl+C to cancel.");
                return;
            }
            try {
                // --- Tier 路由：決定用哪個 agent + model ---
                var keywordTier = tierKeywordDetector.detect(text).orElse(null);
                var tierCtx = TierRouter.Context.builder()
                        .keywordTier(keywordTier)
                        .sessionTier(sessionTier.get())
                        .build();
                var tierSelection = tierRouter.resolve(tierCtx);
                currentTierSelection = tierSelection;

                var model = agentModelRegistry.get(tierSelection.agentId());
                if (model == null) {
                    throw new IllegalStateException("Agent not found: " + tierSelection.agentId());
                }
                // 設計說明：主對話使用 DEV mode — 跟 Claude Code 預設行為一致
                // SDK bug: 有 MCP 時 ClaudeAgentOptions 被 DefaultAgentOptions.from() 覆蓋
                // 導致 disallowedTools 丟失（instanceof ClaudeAgentOptions → false）
                // 隔離由 /dev 指令的 worktree 提供，不依賴工具限制
                var tierOptions = tierOptionsFactory.build(
                        tierSelection.agentId(), tierSelection.model());

                var mcpServers = mcpCatalogBuilder.getServerNames();
                log.info("Tier routing: {} → {} / {} (source: {}), goal: {}, mcpServers: {}",
                        tierSelection.tier(), tierSelection.agentId(), tierSelection.model(),
                        tierSelection.source(),
                        text.length() > 100 ? text.substring(0, 100) + "..." : text,
                        mcpServers.isEmpty() ? "none" : String.join(", ", mcpServers));
                agentState.agentRunning = true;
                contentView.appendLine(new org.jline.utils.AttributedString("\u23f3 thinking...",
                        org.jline.utils.AttributedStyle.DEFAULT.foreground(245)));
                eventLoop.setDirty();

                // 設計說明：主對話不顯示 tier（tier 是給 skill dispatch 用的）。
                // 使用者選的 agent+model 已經顯示在正常 status bar，不需要額外的 tier 標示。

                agentState.agentThread = Thread.startVirtualThread(() -> {
                    long startTime = System.currentTimeMillis();
                    // 設計說明：主對話 Plan Mode — 直接在 CWD 工作，不建 worktree
                    // Worktree 隔離由 Dev Mode（Phase B）處理：skill metadata.grimo.execution=isolated 或 /dev 指令
                    // 參考：Claude Code 預設行為 — 直接在 CWD，worktree 是可選的
                    var projectDir = java.nio.file.Path.of(System.getProperty("user.dir"));
                    try {
                        // 移除 "thinking..." 暫時狀態行
                        contentView.removeLastLine();

                        // 設計說明：直接用 CWD，Plan Mode 下 agent 的 disallowedTools 限制修改
                        var client = AgentClient.builder(model)
                                .mcpServerCatalog(mcpCatalogBuilder.getCatalog())
                                .defaultMcpServers(mcpCatalogBuilder.getServerNames())
                                .defaultWorkingDirectory(projectDir)
                                .build();
                        var response = client.run(text, tierOptions);

                        long duration = System.currentTimeMillis() - startTime;
                        if (response.isSuccessful()) {
                            log.info("Agent response received: success=true, duration={}ms, resultLength={}",
                                    duration, response.getResult() != null ? response.getResult().length() : 0);

                            if (response.getResult() != null && !response.getResult().isBlank()) {
                                contentView.appendAiReply(response.getResult());
                            }
                        } else {
                            log.warn("Agent response received: success=false, duration={}ms, result={}",
                                    duration, response.getResult());
                            contentView.appendError(response.getResult());
                        }
                        sessionWriter.writeAssistantMessage(response.getResult());
                    } catch (Exception e) {
                        long duration = System.currentTimeMillis() - startTime;
                        log.error("Agent call failed: duration={}ms, error={}", duration, e.getMessage(), e);
                        String errorMsg = formatAgentError(e);
                        contentView.appendError(errorMsg);
                    } finally {
                        agentState.agentRunning = false;
                        agentState.agentThread = null;
                        currentTierSelection = null;
                        statusView.setStatusText(tuiEventBridge.getOriginalStatusText());
                        screen.requestFullRedraw();
                        eventLoop.setDirty();
                    }
                });
            } catch (IllegalStateException e) {
                log.warn("Agent routing failed: {}", e.getMessage());
                contentView.appendError(e.getMessage());
            }
        }
    }

    // === 載入步驟方法（靜默執行，無動畫）===

    private void loadSkills() {
        try {
            var skills = skillLoader.loadAll();
            skills.forEach(skill -> {
                skillRegistry.register(skill);
                log.info("Loaded skill: {}", skill.name());
            });
        } catch (Exception e) {
            log.warn("Skill loading failed: {}", e.getMessage());
        }
    }

    private void restoreTasks() {
        try {
            taskSchedulerService.restoreAll();
        } catch (Exception e) {
            log.warn("Task restoration failed: {}", e.getMessage());
        }
    }

    /**
     * 解析預設 agent：先看 config，沒有則按優先順序自動選擇。
     *
     * 設計說明：
     * - 優先順序：claude → codex → gemini（claude 生態最成熟）
     * - 使用者可用 /agent-use 覆寫，寫入 config 後下次啟動沿用
     */
    private static final List<String> AGENT_PRIORITY = List.of("claude", "codex", "gemini");

    private String resolveAgentId(List<AgentModelFactory.DetectionResult> agentResults) {
        String agentId = grimoConfig.getDefaultAgent();
        if (agentId != null) return agentId;

        var availableIds = agentResults.stream()
                .filter(AgentModelFactory.DetectionResult::available)
                .map(AgentModelFactory.DetectionResult::id)
                .toList();

        // 按優先順序找第一個可用的
        for (String preferred : AGENT_PRIORITY) {
            if (availableIds.contains(preferred)) return preferred;
        }
        // 都不在優先清單裡，fallback 到第一個可用的
        return availableIds.isEmpty() ? "no agent" : availableIds.getFirst();
    }

    /**
     * Agent 完成後顯示 diff 摘要（只有 worktree 模式 + 有 commits 時）。
     *
     * 設計說明：
     * - 使用 baseSha（worktree 建立時的 HEAD）做 diff 比較，不依賴分支名稱
     * - 純對話（無 commit）不顯示 branch/diff 資訊
     * - 顯示 merge 指令方便使用者操作
     */
    private void displayDiffSummary(java.nio.file.Path projectDir, WorktreeInfo worktree, long durationMs) {
        try {
            int commitCount = gitHelper.getCommitCount(
                    projectDir, worktree.baseSha(), worktree.branchName());
            if (commitCount == 0) {
                return; // 純對話，不顯示 diff
            }

            String diffStat = gitHelper.getDiffStat(
                    projectDir, worktree.baseSha(), worktree.branchName());
            float seconds = durationMs / 1000f;

            // ⏺ Agent completed (12s)
            var headerLine = new org.jline.utils.AttributedStringBuilder();
            headerLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(2),
                    "\u23fa Agent completed (" + String.format("%.0fs", seconds) + ")");
            contentView.appendLine(headerLine.toAttributedString());

            // Branch: grimo/abc123
            var branchLine = new org.jline.utils.AttributedStringBuilder();
            branchLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                    "  Branch: " + worktree.branchName());
            contentView.appendLine(branchLine.toAttributedString());

            // Files changed (from diffStat)
            if (!diffStat.isBlank()) {
                for (String line : diffStat.split("\n")) {
                    var diffLine = new org.jline.utils.AttributedStringBuilder();
                    diffLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                            "  " + line.trim());
                    contentView.appendLine(diffLine.toAttributedString());
                }
            }

            // Commits: N
            var commitLine = new org.jline.utils.AttributedStringBuilder();
            commitLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                    "  Commits: " + commitCount);
            contentView.appendLine(commitLine.toAttributedString());

            // → git merge grimo/abc123
            var mergeLine = new org.jline.utils.AttributedStringBuilder();
            mergeLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(67),
                    "  \u2192 git merge " + worktree.branchName());
            contentView.appendLine(mergeLine.toAttributedString());

            eventLoop.setDirty();
        } catch (Exception e) {
            log.warn("Failed to display diff summary: {}", e.getMessage());
        }
    }

    /**
     * 格式化 Agent 執行錯誤訊息，依例外類型給出使用者友善的提示。
     */
    private String formatAgentError(Exception e) {
        String name = e.getClass().getSimpleName();
        if (name.contains("NotFoundException")) {
            return "\u26a0 CLI not found. Install the agent CLI and try again.";
        } else if (name.contains("AuthenticationException")) {
            return "\u26a0 Authentication failed. Run the agent's login command.";
        } else if (name.contains("TimeoutException")) {
            return "\u26a0 Agent timed out. Try a simpler goal.";
        }
        return "\u26a0 Agent error: " + e.getMessage();
    }
}
