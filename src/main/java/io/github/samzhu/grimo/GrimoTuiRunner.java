package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.detect.AgentModelFactory;
import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.router.AgentRouter;
import io.github.samzhu.grimo.mcp.McpCatalogBuilder;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import io.github.samzhu.grimo.shared.workspace.WorkspaceManager;
import io.github.samzhu.grimo.skill.loader.SkillLoader;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import io.github.samzhu.grimo.task.scheduler.TaskSchedulerService;
import org.springaicommunity.agents.client.AgentClient;
import org.jline.terminal.MouseEvent;
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

import java.io.PrintWriter;
import java.io.StringWriter;
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
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GrimoTuiRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GrimoTuiRunner.class);

    /** 輸入歷史紀錄 */
    private final List<String> history = new ArrayList<>();
    private int historyIndex = 0;
    private String savedInput = "";

    private final Terminal terminal;
    private final WorkspaceManager workspaceManager;
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
    private SessionWriter sessionWriter;

    public GrimoTuiRunner(Terminal terminal,
                           WorkspaceManager workspaceManager,
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
                           McpCatalogBuilder mcpCatalogBuilder) {
        this.terminal = terminal;
        this.workspaceManager = workspaceManager;
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
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // === Phase 1: Workspace 初始化 ===
        if (!workspaceManager.isInitialized()) {
            workspaceManager.initialize();
        }

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

        // === Phase 3: 準備環境資訊 ===
        String version = getClass().getPackage().getImplementationVersion();
        if (version == null) version = "dev";
        String agentId = resolveAgentId(agentResults);
        String model = grimoConfig.getAgentOption(agentId, "model");
        if (model == null) model = grimoConfig.getDefaultModel();
        if (model == null) model = "unknown";
        String workspacePath = workspaceManager.root().toString()
                .replace(System.getProperty("user.home"), "~");
        long agentCount = agentResults.stream()
                .filter(AgentModelFactory.DetectionResult::available).count();
        int skillCount = skillRegistry.listAll().size();
        int mcpCount = grimoConfig.getMcpServers().size();
        int taskCount = taskSchedulerService.getScheduledTaskIds().size();

        // === Phase 4: 建構 TUI 元件 ===
        contentView = new GrimoContentView();
        String bannerText = bannerRenderer.render(
                version, agentId, model, workspacePath,
                (int) agentCount, skillCount, mcpCount, taskCount);
        contentView.setBannerText(bannerText);

        inputView = new GrimoInputView();

        String statusText = agentId + " · " + model + " │ " + workspacePath
                + " │ " + (int) agentCount + " agent · " + skillCount + " skill · "
                + mcpCount + " mcp · " + taskCount + " task";
        var statusView = new GrimoStatusView(statusText);

        var menuItems = buildMenuItems();
        slashMenuView = new GrimoSlashMenuView(menuItems);

        mcpManagerView = new GrimoMcpManagerView();
        screen = new GrimoScreen(terminal, contentView, inputView, statusView, slashMenuView, mcpManagerView);

        // === Session 對話存檔 ===
        String cwd = System.getProperty("user.dir");
        String encodedCwd = cwd.replaceAll("[^a-zA-Z0-9]", "-");
        var sessionsDir = workspaceManager.root().resolve("projects").resolve(encodedCwd).resolve("sessions");
        sessionWriter = new SessionWriter(sessionsDir);
        sessionWriter.writeSystemMessage(cwd, version, "Grimo TUI session");

        // === Phase 5: 啟動 TUI 事件迴圈（阻塞） ===
        log.debug("Grimo TUI setup complete, starting raw JLine event loop.");
        eventLoop = new GrimoEventLoop(terminal, screen, new TuiKeyHandler());
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
            if (event.getType() == MouseEvent.Type.Wheel) {
                if (event.getButton() == MouseEvent.Button.WheelUp) {
                    contentView.scrollUp(3);
                } else if (event.getButton() == MouseEvent.Button.WheelDown) {
                    contentView.scrollDown(3);
                }
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
                var model = agentRouter.route(null);
                var mcpServers = mcpCatalogBuilder.getServerNames();
                log.info("Routing to agent, goal: {}, mcpServers: {}",
                        text.length() > 100 ? text.substring(0, 100) + "..." : text,
                        mcpServers.isEmpty() ? "none" : String.join(", ", mcpServers));
                agentRunning = true;
                contentView.appendLine(new org.jline.utils.AttributedString("\u23f3 thinking...",
                        org.jline.utils.AttributedStyle.DEFAULT.foreground(245)));
                eventLoop.setDirty();

                agentThread = Thread.startVirtualThread(() -> {
                    long startTime = System.currentTimeMillis();
                    try {
                        // 設計說明：使用 builder pattern 傳入 MCP catalog，讓 CLI agent 自動帶上 MCP tools
                        // McpServerCatalog 由 Portable MCP 機制自動轉成各 CLI 原生格式
                        // 參考：javap AgentClient$Builder → mcpServerCatalog() + defaultMcpServers()
                        // 設計說明：每次取 McpCatalogBuilder 最新快取，/mcp-add 後即時生效
                        // volatile 保證 command thread 寫入後 virtual thread 能看到新值
                        var client = AgentClient.builder(model)
                                .mcpServerCatalog(mcpCatalogBuilder.getCatalog())
                                .defaultMcpServers(mcpCatalogBuilder.getServerNames())
                                .build();
                        var response = client
                                .goal(text)
                                .workingDirectory(java.nio.file.Path.of(System.getProperty("user.dir")))
                                .run();

                        // 移除 "thinking..." 暫時狀態行
                        contentView.removeLastLine();

                        long duration = System.currentTimeMillis() - startTime;
                        if (response.isSuccessful()) {
                            log.info("Agent response received: success=true, duration={}ms, resultLength={}",
                                    duration, response.getResult() != null ? response.getResult().length() : 0);
                            contentView.appendAiReply(response.getResult());
                        } else {
                            log.warn("Agent response received: success=false, duration={}ms, result={}",
                                    duration, response.getResult());
                            contentView.appendError(response.getResult());
                        }
                        sessionWriter.writeAssistantMessage(response.getResult());
                    } catch (Exception e) {
                        contentView.removeLastLine(); // 移除 "thinking..."
                        long duration = System.currentTimeMillis() - startTime;
                        log.error("Agent call failed: duration={}ms, error={}", duration, e.getMessage(), e);
                        String errorMsg = formatAgentError(e);
                        contentView.appendError(errorMsg);
                    } finally {
                        agentRunning = false;
                        agentThread = null;
                        // agent 完成後 content 區大量變動，強制全螢幕重繪
                        // 修正：JLine Display diff 可能遺漏 input 行的品牌色更新
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
            skills.forEach(skillRegistry::register);
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

    private String resolveAgentId(List<AgentModelFactory.DetectionResult> agentResults) {
        String agentId = grimoConfig.getDefaultAgent();
        if (agentId == null) {
            agentId = agentResults.stream()
                    .filter(AgentModelFactory.DetectionResult::available)
                    .map(AgentModelFactory.DetectionResult::id)
                    .findFirst().orElse("no agent");
        }
        return agentId;
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
