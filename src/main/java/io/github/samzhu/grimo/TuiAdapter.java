package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.AgentCommands;
import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.command.InputMetadata;
import io.github.samzhu.grimo.command.InputPort;
import io.github.samzhu.grimo.config.GrimoConfig;
import io.github.samzhu.grimo.home.GrimoHome;
import io.github.samzhu.grimo.mcp.McpCatalogBuilder;
import io.github.samzhu.grimo.project.ProjectContext;
import io.github.samzhu.grimo.shared.sandbox.GitHelper;
import io.github.samzhu.grimo.shared.sandbox.WorktreeProvisioner;
import io.github.samzhu.grimo.shared.session.SessionWriter;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import io.github.samzhu.grimo.task.scheduler.TaskSchedulerService;
import io.github.samzhu.grimo.tui.TuiEventBridge;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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

import java.util.ArrayList;
import java.util.List;

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
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TuiAdapter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TuiAdapter.class);

    private final Terminal terminal;
    private final GrimoHome grimoHome;
    private final ProjectContext projectContext;
    private final GrimoConfig grimoConfig;
    private final AgentModelRegistry agentModelRegistry;
    private final SkillRegistry skillRegistry;
    private final TaskSchedulerService taskSchedulerService;
    private final Banner banner;
    private final CommandParser commandParser;
    private final CommandExecutor commandExecutor;
    private final CommandRegistry commandRegistry;
    private final McpCatalogBuilder mcpCatalogBuilder;
    private final WorktreeProvisioner worktreeProvisioner;
    private final GitHelper gitHelper;
    private final TuiEventBridge tuiEventBridge;
    private final ChatDispatcher chatDispatcher;
    private final InputPort inputPort;

    /** Status bar 元件（run 時初始化，需在 agent thread 中更新） */
    private StatusView statusView;

    /**
     * AI 對話併發控制：封裝在 AgentStateRef，供 TuiKeyHandler 在 Ctrl+C 時中斷 agent。
     * 設計說明：agentRunning / agentThread 同時被 processInput（TuiAdapter）和
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

    public TuiAdapter(Terminal terminal,
                           GrimoHome grimoHome,
                           ProjectContext projectContext,
                           SessionWriter sessionWriter,
                           GrimoConfig grimoConfig,
                           AgentModelRegistry agentModelRegistry,
                           SkillRegistry skillRegistry,
                           TaskSchedulerService taskSchedulerService,
                           Banner banner,
                           CommandParser commandParser,
                           CommandExecutor commandExecutor,
                           CommandRegistry commandRegistry,
                           McpCatalogBuilder mcpCatalogBuilder,
                           WorktreeProvisioner worktreeProvisioner,
                           GitHelper gitHelper,
                           TuiEventBridge tuiEventBridge,
                           ChatDispatcher chatDispatcher,
                           InputPort inputPort) {
        this.terminal = terminal;
        this.grimoHome = grimoHome;
        this.projectContext = projectContext;
        this.sessionWriter = sessionWriter;
        this.grimoConfig = grimoConfig;
        this.agentModelRegistry = agentModelRegistry;
        this.skillRegistry = skillRegistry;
        this.taskSchedulerService = taskSchedulerService;
        this.banner = banner;
        this.commandParser = commandParser;
        this.commandExecutor = commandExecutor;
        this.commandRegistry = commandRegistry;
        this.mcpCatalogBuilder = mcpCatalogBuilder;
        this.worktreeProvisioner = worktreeProvisioner;
        this.gitHelper = gitHelper;
        this.tuiEventBridge = tuiEventBridge;
        this.chatDispatcher = chatDispatcher;
        this.inputPort = inputPort;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 設計說明：Phase 1（Home/Project 初始化）與 Phase 2（Agent 偵測、Skill 載入、MCP、Task、Sandbox）
        // 已移至 GrimoStartupRunner.startupInitRunner（Order HIGHEST_PRECEDENCE + 1，先執行），
        // 本 runner（Order HIGHEST_PRECEDENCE + 2，後執行）在初始化完成後建構 TUI。

        // === 準備環境資訊 ===
        String version = getClass().getPackage().getImplementationVersion();
        if (version == null) version = "dev";
        String agentId = resolveAgentId();
        String model = grimoConfig.getAgentOption(agentId, "model");
        if (model == null) model = grimoConfig.getDefaultModel();
        if (model == null) model = AgentCommands.RECOMMENDED_MODELS.getOrDefault(agentId, "unknown");
        String projectPath = projectContext.displayPath();
        long agentCount = agentModelRegistry.listAll().values().stream()
                .filter(m -> m.isAvailable()).count();
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

        // 繫結 TUI 元件到 ChatDispatcher（AI 對話 dispatch 需要直接更新 TUI）
        chatDispatcher.bindTui(contentView, statusView, eventLoop, agentState);

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
     * 從 InputPort（CommandDispatcher + SkillRegistry）建構斜線指令選單項目。
     *
     * 設計說明（六角架構）：
     * - TuiAdapter 是 Driving Adapter，透過 InputPort 取得可用指令清單
     * - 不直接讀 CommandRegistry（Spring Shell），改讀 CommandDispatcher（Core）
     * - @mentions 不顯示在斜線選單（屬於 agent mention 路徑）
     */
    private List<SlashMenu.MenuItem> buildMenuItems() {
        return inputPort.listAvailableCommands().stream()
                .filter(e -> !e.name().startsWith("@"))
                .map(e -> new SlashMenu.MenuItem(e.name(), e.description()))
                .toList();
    }

    /**
     * 處理使用者輸入：TUI 專屬攔截直接處理，其餘透過 InputPort 進入 Core。
     *
     * 設計說明（六角架構）：
     * <pre>
     * TUI input
     *   ├─ TUI 專屬攔截（/exit、/mcp 無參數、/agent-use 無參數）→ 直接處理（不進 Core）
     *   └─ 其他輸入 → sessionWriter.writeUserMessage() + inputPort.handleInput()
     *        → InputHandler（Core）
     *            ├─ /command → CommandDispatcher → ResponseCallback → contentView
     *            └─ AI text  → ChatDispatcher（含 Virtual Thread，直接更新 TUI）
     * </pre>
     *
     * Adapter 呼叫 Port（不走 IncomingMessageEvent），符合六角架構：Adapter → Port → Core。
     */
    private void processInput(String text) {
        // TUI 專屬 overlays（不進 InputPort）
        if (text.equals("/exit")) { eventLoop.stop(); return; }
        if (text.equals("/agent-use")) { showAgentPicker(); return; }
        if (text.equals("/mcp")) { tuiKeyHandler.openMcpManager(); return; }

        contentView.appendUserInput(text);
        sessionWriter.writeUserMessage(text);

        // 六角架構：Adapter 直接呼叫 Port（不經 IncomingMessageEvent）
        inputPort.handleInput(text,
                InputMetadata.tui(sessionWriter.getSessionId()),
                result -> {
                    contentView.appendCommandOutput(result);
                    eventLoop.setDirty();
                });
    }

    /**
     * 解析預設 agent：先看 config，沒有則從 AgentModelRegistry 按優先順序自動選擇。
     *
     * 設計說明：
     * - 優先順序：claude → codex → gemini（claude 生態最成熟）
     * - 使用者可用 /agent-use 覆寫，寫入 config 後下次啟動沿用
     * - 啟動初始化已移至 GrimoStartupRunner.startupInitRunner，registry 在此已有資料
     */
    private static final List<String> AGENT_PRIORITY = List.of("claude", "codex", "gemini");

    private String resolveAgentId() {
        String agentId = grimoConfig.getDefaultAgent();
        if (agentId != null) return agentId;

        var availableIds = agentModelRegistry.listAll().entrySet().stream()
                .filter(e -> e.getValue().isAvailable())
                .map(java.util.Map.Entry::getKey)
                .toList();

        // 按優先順序找第一個可用的
        for (String preferred : AGENT_PRIORITY) {
            if (availableIds.contains(preferred)) return preferred;
        }
        // 都不在優先清單裡，fallback 到第一個可用的
        return availableIds.isEmpty() ? "no agent" : availableIds.getFirst();
    }

}
