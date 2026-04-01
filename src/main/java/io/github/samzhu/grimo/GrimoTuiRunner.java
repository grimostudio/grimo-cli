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
import io.github.samzhu.grimo.shared.sandbox.WorkspaceProvisioner;
import io.github.samzhu.grimo.shared.sandbox.WorktreeInfo;
import io.github.samzhu.grimo.shared.sandbox.SandboxDetector;
import io.github.samzhu.grimo.config.GrimoConfig;
import io.github.samzhu.grimo.shared.event.AgentSwitchedEvent;
import io.github.samzhu.grimo.shared.event.DevModeEnteredEvent;
import io.github.samzhu.grimo.shared.event.DevModeCompletedEvent;
import io.github.samzhu.grimo.shared.event.McpCatalogChangedEvent;
import io.github.samzhu.grimo.home.GrimoHome;
import io.github.samzhu.grimo.project.ProjectContext;
import io.github.samzhu.grimo.skill.loader.SkillLoader;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import io.github.samzhu.grimo.shared.session.SessionWriter;
import io.github.samzhu.grimo.task.scheduler.TaskSchedulerService;
import org.springaicommunity.agents.client.AgentClient;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.CommandExecutor;
import org.springframework.shell.core.command.CommandParser;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.stereotype.Component;

import io.github.samzhu.grimo.shared.tui.AutoScroller;
import io.github.samzhu.grimo.shared.tui.ClipboardWriter;
import io.github.samzhu.grimo.shared.tui.TextSelection;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
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

    /** 輸入歷史紀錄 */
    private final List<String> history = new ArrayList<>();
    private int historyIndex = 0;
    private String savedInput = "";

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
    private final BannerRenderer bannerRenderer;
    private final CommandParser commandParser;
    private final CommandExecutor commandExecutor;
    private final CommandRegistry commandRegistry;
    private final McpCatalogBuilder mcpCatalogBuilder;
    private final WorkspaceProvisioner workspaceProvisioner;
    private final GitHelper gitHelper;
    private final SandboxDetector sandboxDetector;
    private final TierRouter tierRouter;
    private final TierKeywordDetector tierKeywordDetector;
    private final TierOptionsFactory tierOptionsFactory;
    private final AtomicReference<Tier> sessionTier;

    /** 本輪 tier（顯示用）：每次 processInput 重設 */
    private volatile TierSelection currentTierSelection;

    /** Status bar 元件（run 時初始化，需在 agent thread 中更新） */
    private GrimoStatusView statusView;
    private String originalStatusText;

    /** AI 對話併發控制：確保同時只有一個 agent 執行 */
    private volatile boolean agentRunning = false;
    private volatile Thread agentThread = null;

    /** Double Ctrl+C 退出：第一次顯示提示，2 秒內第二次才真的退出（對齊 Claude Code UX） */
    private long lastCtrlCTime = 0;
    private static final long CTRL_C_EXIT_WINDOW_MS = 2000;

    // TUI 元件（run 時初始化）
    private GrimoContentView contentView;
    private GrimoInputView inputView;
    private GrimoSlashMenuView slashMenuView;
    private GrimoMcpManagerView mcpManagerView;
    private GrimoScreen screen;
    private GrimoEventLoop eventLoop;
    private final SessionWriter sessionWriter;
    private TextSelection textSelection;
    private AutoScroller autoScroller;
    private ClipboardWriter clipboardWriter;

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
                           BannerRenderer bannerRenderer,
                           CommandParser commandParser,
                           CommandExecutor commandExecutor,
                           CommandRegistry commandRegistry,
                           McpCatalogBuilder mcpCatalogBuilder,
                           WorkspaceProvisioner workspaceProvisioner,
                           GitHelper gitHelper,
                           SandboxDetector sandboxDetector,
                           TierRouter tierRouter,
                           TierKeywordDetector tierKeywordDetector,
                           TierOptionsFactory tierOptionsFactory,
                           AtomicReference<Tier> sessionTier) {
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
        this.bannerRenderer = bannerRenderer;
        this.commandParser = commandParser;
        this.commandExecutor = commandExecutor;
        this.commandRegistry = commandRegistry;
        this.mcpCatalogBuilder = mcpCatalogBuilder;
        this.workspaceProvisioner = workspaceProvisioner;
        this.gitHelper = gitHelper;
        this.sandboxDetector = sandboxDetector;
        this.tierRouter = tierRouter;
        this.tierKeywordDetector = tierKeywordDetector;
        this.tierOptionsFactory = tierOptionsFactory;
        this.sessionTier = sessionTier;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // === Phase 1: Workspace 初始化 ===
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
        String workspacePath = projectContext.displayPath();
        long agentCount = agentResults.stream()
                .filter(AgentModelFactory.DetectionResult::available).count();
        int skillCount = skillRegistry.listAll().size();
        int mcpCount = grimoConfig.getMcpServers().size();
        int taskCount = taskSchedulerService.getScheduledTaskIds().size();

        // === Phase 4: 建構 TUI 元件 ===
        contentView = new GrimoContentView();
        String bannerText = bannerRenderer.render(
                version, agentId, model, workspacePath,
                (int) agentCount, skillCount, mcpCount, taskCount,
                terminal.getWidth());
        contentView.setBannerText(bannerText);

        inputView = new GrimoInputView();

        String statusText = agentId + " · " + model + " │ " + workspacePath
                + " │ " + (int) agentCount + " agent · " + skillCount + " skill · "
                + mcpCount + " mcp · " + taskCount + " task";
        this.statusView = new GrimoStatusView(statusText);
        this.originalStatusText = statusText;

        var menuItems = buildMenuItems();
        slashMenuView = new GrimoSlashMenuView(menuItems);

        mcpManagerView = new GrimoMcpManagerView();
        textSelection = new TextSelection();
        clipboardWriter = new ClipboardWriter();
        screen = new GrimoScreen(terminal, contentView, inputView, statusView,
                slashMenuView, mcpManagerView, textSelection);

        // === Session 對話存檔 ===
        sessionWriter.writeSystemMessage(
            projectContext.path().toString(), version, "Grimo TUI session");

        // === Phase 5: 啟動 TUI 事件迴圈（阻塞） ===
        log.debug("Grimo TUI setup complete, starting raw JLine event loop.");
        eventLoop = new GrimoEventLoop(terminal, screen, new TuiKeyHandler());

        autoScroller = new AutoScroller(
            () -> contentView.scrollUp(1),
            () -> contentView.scrollDown(1),
            delta -> textSelection.dragTo(
                    textSelection.getCursorRow() + delta,
                    textSelection.getCursorCol()),
            () -> eventLoop.setDirty()
        );

        eventLoop.setOnResize(() -> {
            textSelection.cancel();
            autoScroller.stop();
        });

        // StatusView 的暫時訊息消失後需要觸發重繪
        statusView.setDirtyCallback(() -> eventLoop.setDirty());

        eventLoop.run();

        // 事件迴圈結束（/exit），終止 Spring Boot JVM
        System.exit(0);
    }

    /**
     * 鍵盤/滑鼠事件處理器：實作 GrimoEventLoop.KeyHandler。
     *
     * 設計說明：
     * - 分兩個模式：斜線選單模式（slashMenuVisible）和一般模式
     * - 滑鼠滾輪事件直接轉為 content 捲動
     * - 所有狀態更新後由 eventLoop.setDirty() 觸發重繪
     */
    private class TuiKeyHandler implements GrimoEventLoop.KeyHandler {

        @Override
        public void handleKey(String operation, String lastBinding) {
            // Ctrl+C 有選取時 → 複製到剪貼簿（參考 Claude Code 行為）
            // 設計說明：macOS raw mode 下，Ctrl+C 可能被 BindingReader 當成 CHAR（\003），
            // 而非匹配到 OP_CTRL_C binding。同時檢查 operation 和 lastBinding 確保捕捉到。
            log.info("[KEY] op={} lastBinding={} selectionActive={}",
                    operation, lastBinding != null ? String.format("0x%02x", (int) lastBinding.charAt(0)) : "null",
                    textSelection.isActive());
            boolean isCtrlC = GrimoEventLoop.OP_CTRL_C.equals(operation)
                    || (lastBinding != null && lastBinding.length() == 1 && lastBinding.charAt(0) == '\003');
            if (textSelection.isActive()) {
                if (isCtrlC) {
                    var buffer = screen.getScreenBuffer();
                    log.info("[SELECT] Ctrl+C: buffer={} size={} range={}",
                            buffer != null ? "present" : "NULL",
                            buffer != null ? buffer.size() : 0,
                            textSelection.getRange());
                    if (buffer != null) {
                        String text = textSelection.finish(buffer);
                        log.info("[SELECT] Ctrl+C: extracted text='{}' len={}",
                                text != null ? text.substring(0, Math.min(80, text.length())) : "null",
                                text != null ? text.length() : 0);
                        if (text != null && !text.isEmpty()) {
                            clipboardWriter.copy(terminal, text);
                            statusView.setTemporaryMessage(
                                    "✓ Copied!", Duration.ofSeconds(2));
                            log.info("[SELECT] Ctrl+C copied to clipboard: len={}", text.length());
                        } else {
                            log.info("[SELECT] Ctrl+C: text was empty, nothing copied");
                        }
                    } else {
                        log.info("[SELECT] Ctrl+C: buffer was null, canceling");
                        textSelection.cancel();
                    }
                    autoScroller.stop();
                    return; // 不傳遞 Ctrl+C 到下層（不觸發退出流程）
                }
                // 其他鍵 → 取消選取
                log.info("[SELECT] Key '{}' cancels selection", operation);
                textSelection.cancel();
                autoScroller.stop();
            }
            if (screen.isMcpManagerVisible()) {
                handleMcpManagerKey(operation, lastBinding);
            } else if (screen.isSlashMenuVisible()) {
                handleSlashMenuKey(operation, lastBinding);
            } else {
                handleNormalKey(operation, lastBinding);
            }
        }

        @Override
        public void handleMouse(MouseEvent event) {
            // Overlay 可見時只保留滾輪
            if (screen.isSlashMenuVisible() || screen.isMcpManagerVisible()) {
                if (event.getType() == MouseEvent.Type.Wheel) {
                    handleWheel(event);
                }
                return;
            }

            // DEBUG: 追蹤所有滑鼠事件類型
            log.info("[MOUSE] type={} button={} x={} y={} modifiers={}",
                    event.getType(), event.getButton(), event.getX(), event.getY(), event.getModifiers());

            switch (event.getType()) {
                case Wheel -> handleWheel(event);
                case Pressed -> {
                    if (event.getButton() == MouseEvent.Button.Button1) {
                        int contentHeight = screen.getRows() - 4; // 3 input + 1 status
                        int bufferRow = screen.screenToBuffer(event.getY(), contentHeight);
                        log.info("[SELECT] Pressed: screenY={} bufferRow={} x={} contentHeight={}",
                                event.getY(), bufferRow, event.getX(), contentHeight);
                        if (bufferRow >= 0) {
                            textSelection.startAt(bufferRow, event.getX());
                        }
                        autoScroller.stop();
                    }
                }
                case Dragged -> {
                    if (textSelection.isActive()) {
                        int contentHeight = screen.getRows() - 4;
                        int bufferRow = screen.screenToBuffer(event.getY(), contentHeight);
                        if (bufferRow >= 0) {
                            textSelection.dragTo(bufferRow, event.getX());
                        }
                        autoScroller.update(event.getY(), contentHeight);
                    }
                }
                case Released -> {
                    // 放開滑鼠 → 自動複製到剪貼簿 + 清除選取
                    // 設計說明：macOS 上 Cmd+C 被終端攔截、Ctrl+C 是中斷信號，
                    // 都無法可靠地作為「複製選取文字」的觸發。
                    // 改為 auto-copy on release，跟原始設計一致。
                    if (event.getButton() == MouseEvent.Button.Button1 && textSelection.isActive()) {
                        autoScroller.stop();
                        int contentHeight = screen.getRows() - 4;
                        int bufferRow = screen.screenToBuffer(event.getY(), contentHeight);
                        if (bufferRow >= 0) {
                            textSelection.dragTo(bufferRow, event.getX());
                        }
                        var buffer = screen.getScreenBuffer();
                        if (buffer != null) {
                            String text = textSelection.finish(buffer);
                            log.info("[SELECT] Released: extracted text='{}' len={}",
                                    text != null ? text.substring(0, Math.min(80, text.length())) : "null",
                                    text != null ? text.length() : 0);
                            if (text != null && !text.isEmpty()) {
                                clipboardWriter.copy(terminal, text);
                                statusView.setTemporaryMessage(
                                        "✓ Copied!", Duration.ofSeconds(2));
                                eventLoop.setDirty();
                            }
                        } else {
                            textSelection.cancel();
                        }
                    }
                }
                default -> {}
            }
        }

        private void handleWheel(MouseEvent event) {
            if (event.getButton() == MouseEvent.Button.WheelUp) {
                contentView.scrollUp(3);
            } else if (event.getButton() == MouseEvent.Button.WheelDown) {
                contentView.scrollDown(3);
            }
        }
    }

    /**
     * 斜線指令選單模式的鍵盤處理。
     */
    private void handleSlashMenuKey(String operation, String lastBinding) {
        switch (operation) {
            case GrimoEventLoop.OP_UP -> slashMenuView.moveUp();
            case GrimoEventLoop.OP_DOWN -> slashMenuView.moveDown();
            case GrimoEventLoop.OP_TAB, GrimoEventLoop.OP_ENTER -> {
                String selected = slashMenuView.getSelected();
                if (selected != null) {
                    inputView.insertSlashCommand(selected);
                }
                screen.setSlashMenuVisible(false);
            }
            case GrimoEventLoop.OP_BACKSPACE -> {
                inputView.deleteChar();
                String slashToken = inputView.getCurrentSlashToken();
                if (slashToken == null || !slashMenuView.hasItems()) {
                    screen.setSlashMenuVisible(false);
                } else {
                    slashMenuView.filter(slashToken.substring(1));
                    if (!slashMenuView.hasItems()) {
                        screen.setSlashMenuVisible(false);
                    }
                }
            }
            case GrimoEventLoop.OP_CTRL_C -> screen.setSlashMenuVisible(false);
            case GrimoEventLoop.OP_CHAR -> {
                insertCharFromBinding(lastBinding);
                String slashToken = inputView.getCurrentSlashToken();
                if (slashToken != null) {
                    slashMenuView.filter(slashToken.substring(1));
                    if (!slashMenuView.hasItems()) {
                        screen.setSlashMenuVisible(false);
                    }
                } else {
                    screen.setSlashMenuVisible(false);
                }
            }
        }
    }

    /**
     * MCP Manager overlay 模式的鍵盤處理。
     *
     * 設計說明：
     * - ↑↓ 導航 server 列表（不 wrap）
     * - d 直接刪除選中 server，即時重建 catalog
     * - a 關閉 overlay，Input 區自動填入 "/mcp-add "
     * - Esc/Ctrl+C 關閉 overlay
     */
    private void handleMcpManagerKey(String operation, String lastBinding) {
        switch (operation) {
            case GrimoEventLoop.OP_UP -> mcpManagerView.moveUp();
            case GrimoEventLoop.OP_DOWN -> mcpManagerView.moveDown();
            case GrimoEventLoop.OP_ESC, GrimoEventLoop.OP_CTRL_C -> {
                screen.setMcpManagerVisible(false);
                screen.requestFullRedraw();
            }
            case GrimoEventLoop.OP_CHAR -> {
                if (lastBinding != null && lastBinding.length() == 1) {
                    char c = lastBinding.charAt(0);
                    if (c == 'd' || c == 'D') {
                        String name = mcpManagerView.getSelectedName();
                        if (name != null) {
                            grimoConfig.removeMcpServer(name);
                            mcpCatalogBuilder.rebuild();
                            mcpManagerView.load(grimoConfig.getMcpServers());
                        }
                    } else if (c == 'a' || c == 'A') {
                        screen.setMcpManagerVisible(false);
                        screen.requestFullRedraw();
                        inputView.setText("/mcp-add ");
                    }
                }
            }
        }
        eventLoop.setDirty();
    }

    /**
     * 一般模式的鍵盤處理。
     */
    private void handleNormalKey(String operation, String lastBinding) {
        switch (operation) {
            case GrimoEventLoop.OP_ENTER -> {
                String text = inputView.getText().trim();
                if (!text.isEmpty()) {
                    log.debug("ENTER pressed, text='{}', lastBinding bytes={}", text,
                            lastBinding != null ? java.util.HexFormat.of().formatHex(lastBinding.getBytes()) : "null");
                    history.add(text);
                    historyIndex = history.size();
                    savedInput = "";
                    contentView.appendUserInput(text);
                    inputView.clear();
                    // content 和 input 同時變動時，強制全螢幕重繪
                    // 避免 JLine Display diff 遺漏 input 行更新
                    screen.requestFullRedraw();
                    sessionWriter.writeUserMessage(text);
                    processInput(text);
                }
            }
            case GrimoEventLoop.OP_UP, GrimoEventLoop.OP_DOWN -> {
                // ↑/↓ 在一般模式暫不處理（未來可加歷史瀏覽）
            }
            case GrimoEventLoop.OP_CTRL_U -> {
                int halfPage = Math.max(1, (terminal.getHeight() - 4) / 2);
                contentView.scrollUp(halfPage);
            }
            case GrimoEventLoop.OP_CTRL_D -> {
                int halfPage = Math.max(1, (terminal.getHeight() - 4) / 2);
                contentView.scrollDown(halfPage);
            }
            case GrimoEventLoop.OP_CTRL_C -> {
                if (agentRunning && agentThread != null) {
                    // Agent 執行中 → 取消 agent
                    agentThread.interrupt();
                    contentView.appendError("Agent cancelled.");
                    eventLoop.setDirty();
                } else if (!inputView.getText().isEmpty()) {
                    // Input 有文字 → 清空（第一層）
                    inputView.clear();
                    historyIndex = history.size();
                    savedInput = "";
                } else {
                    // Input 空 → double Ctrl+C 退出（對齊 Claude Code UX）
                    long now = System.currentTimeMillis();
                    if (now - lastCtrlCTime < CTRL_C_EXIT_WINDOW_MS) {
                        eventLoop.stop();
                    } else {
                        lastCtrlCTime = now;
                        contentView.appendLine(new org.jline.utils.AttributedString(
                                "  Press Ctrl+C again to exit",
                                org.jline.utils.AttributedStyle.DEFAULT.foreground(245)));
                        eventLoop.setDirty();
                    }
                }
            }
            case GrimoEventLoop.OP_BACKSPACE -> {
                inputView.deleteChar();
                // backspace 後檢查是否該重開斜線選單
                // 修正：選單因過濾為空而關閉後，backspace 回到有效 token 時應重開
                tryReopenSlashMenu();
            }
            case GrimoEventLoop.OP_DELETE -> inputView.deleteForward();
            case GrimoEventLoop.OP_LEFT -> inputView.moveCursorLeft();
            case GrimoEventLoop.OP_RIGHT -> inputView.moveCursorRight();
            case GrimoEventLoop.OP_CHAR -> {
                log.debug("OP_CHAR, lastBinding bytes={}, text='{}'",
                        lastBinding != null ? java.util.HexFormat.of().formatHex(lastBinding.getBytes()) : "null",
                        lastBinding);
                insertCharFromBinding(lastBinding);
                if (inputView.shouldOpenSlashMenu()) {
                    slashMenuView.filterAll();
                    screen.setSlashMenuVisible(true);
                }
            }
        }
    }

    /**
     * 從 BindingReader 的 lastBinding 插入字元到 inputView。
     */
    private void insertCharFromBinding(String lastBinding) {
        if (lastBinding != null && !lastBinding.isEmpty()) {
            for (int i = 0; i < lastBinding.length(); i++) {
                char c = lastBinding.charAt(i);
                // 允許所有可印刷字元（含 Unicode：中文、日文等），排除控制字元
                if (!Character.isISOControl(c)) {
                    inputView.insertChar(c);
                }
            }
        }
    }

    /**
     * 檢查 backspace 後是否該重開斜線選單。
     * 修正：選單因過濾結果為空而關閉後，backspace 回到有效 slash token 時應重開選單。
     */
    private void tryReopenSlashMenu() {
        String slashToken = inputView.getCurrentSlashToken();
        if (slashToken != null) {
            slashMenuView.filter(slashToken.substring(1));
            if (slashMenuView.hasItems()) {
                screen.setSlashMenuVisible(true);
            }
        }
    }

    /**
     * 開啟 MCP Manager overlay。
     * 互斥保證：先關閉 slash menu 再開啟 MCP Manager。
     */
    private void openMcpManager() {
        screen.setSlashMenuVisible(false);
        mcpManagerView.load(grimoConfig.getMcpServers());
        screen.setMcpManagerVisible(true);
    }

    /**
     * 從 CommandRegistry + SkillRegistry 建構斜線指令選單項目。
     */
    private List<GrimoSlashMenuView.MenuItem> buildMenuItems() {
        var items = new java.util.ArrayList<GrimoSlashMenuView.MenuItem>();

        commandRegistry.getCommandsByPrefix("").stream()
                .filter(cmd -> !"chat".equals(cmd.getName()))
                .forEach(cmd -> items.add(new GrimoSlashMenuView.MenuItem(
                        cmd.getName(), cmd.getDescription())));

        skillRegistry.listAll().forEach(skill ->
                items.add(new GrimoSlashMenuView.MenuItem(
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
            openMcpManager();
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
            if (agentRunning) {
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
                agentRunning = true;
                contentView.appendLine(new org.jline.utils.AttributedString("\u23f3 thinking...",
                        org.jline.utils.AttributedStyle.DEFAULT.foreground(245)));
                eventLoop.setDirty();

                // 設計說明：主對話不顯示 tier（tier 是給 skill dispatch 用的）。
                // 使用者選的 agent+model 已經顯示在正常 status bar，不需要額外的 tier 標示。

                agentThread = Thread.startVirtualThread(() -> {
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
                        agentRunning = false;
                        agentThread = null;
                        currentTierSelection = null;
                        statusView.setStatusText(originalStatusText);
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

    /**
     * 設計說明：Command → Event → TUI 解耦
     * AgentCommands.use() publish event → 這裡自動刷新 status bar
     * 不再需要 command 執行後手動呼叫 refreshStatusBar()
     */
    @EventListener
    void on(AgentSwitchedEvent event) {
        if (statusView == null) return; // TUI 尚未初始化
        refreshStatusBar();
        if (eventLoop != null) eventLoop.setDirty();
    }

    @EventListener
    void on(McpCatalogChangedEvent event) {
        if (statusView == null) return;
        refreshStatusBar();
        if (eventLoop != null) eventLoop.setDirty();
    }

    @EventListener
    void on(DevModeEnteredEvent event) {
        if (contentView == null) return;

        var wtLine = new org.jline.utils.AttributedStringBuilder();
        wtLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(2), "\u26a1 ");
        wtLine.append("Dev Mode (" + event.branchName() + ")");
        contentView.appendLine(wtLine.toAttributedString());

        var statusLine = new org.jline.utils.AttributedStringBuilder();
        statusLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                "  \u2514 Worktree created, agent working with full access...");
        contentView.appendLine(statusLine.toAttributedString());

        if (eventLoop != null) eventLoop.setDirty();
    }

    @EventListener
    void on(DevModeCompletedEvent event) {
        if (contentView == null) return;

        float seconds = event.durationMs() / 1000f;

        if (event.hasChanges()) {
            var headerLine = new org.jline.utils.AttributedStringBuilder();
            headerLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(2),
                    "\u23fa Dev Mode completed (" + String.format("%.0fs", seconds) + ")");
            contentView.appendLine(headerLine.toAttributedString());

            var branchLine = new org.jline.utils.AttributedStringBuilder();
            branchLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                    "  Branch: " + event.branchName());
            contentView.appendLine(branchLine.toAttributedString());

            if (!event.diffStat().isBlank()) {
                for (String line : event.diffStat().split("\n")) {
                    var diffLine = new org.jline.utils.AttributedStringBuilder();
                    diffLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                            "  " + line.trim());
                    contentView.appendLine(diffLine.toAttributedString());
                }
            }

            var commitLine = new org.jline.utils.AttributedStringBuilder();
            commitLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                    "  Commits: " + event.commitCount());
            contentView.appendLine(commitLine.toAttributedString());

            var mergeLine = new org.jline.utils.AttributedStringBuilder();
            mergeLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(67),
                    "  \u2192 git merge " + event.branchName());
            contentView.appendLine(mergeLine.toAttributedString());
        } else {
            var line = new org.jline.utils.AttributedStringBuilder();
            line.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                    "\u23fa Dev Mode completed (" + String.format("%.0fs", seconds) + ") \u2014 no file changes");
            contentView.appendLine(line.toAttributedString());
        }

        if (event.result() != null && !event.result().isBlank()) {
            contentView.appendAiReply(event.result());
        }

        contentView.appendLine(org.jline.utils.AttributedString.EMPTY);
        if (eventLoop != null) eventLoop.setDirty();
    }

    /**
     * 從 config 重新讀取 agent/model，更新 status bar。
     * /agent-use 等指令執行後呼叫，確保即時反映切換結果。
     */
    private void refreshStatusBar() {
        String agentId = grimoConfig.getDefaultAgent();
        if (agentId == null) agentId = resolveAgentId(List.of());
        String model = grimoConfig.getAgentOption(agentId, "model");
        if (model == null) model = grimoConfig.getDefaultModel();
        if (model == null) model = AgentCommands.RECOMMENDED_MODELS.getOrDefault(agentId, "unknown");

        String workspacePath = projectContext.displayPath();
        long agentCount = agentModelRegistry.listAll().values().stream()
                .filter(m -> m.isAvailable()).count();
        int skillCount = skillRegistry.listAll().size();
        int mcpCount = grimoConfig.getMcpServers().size();
        int taskCount = taskSchedulerService.getScheduledTaskIds().size();

        String newStatus = agentId + " · " + model + " │ " + workspacePath
                + " │ " + (int) agentCount + " agent · " + skillCount + " skill · "
                + mcpCount + " mcp · " + taskCount + " task";
        this.originalStatusText = newStatus;
        statusView.setStatusText(newStatus);
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
