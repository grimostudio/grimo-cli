package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.detect.AgentModelFactory;
import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.router.AgentRouter;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import io.github.samzhu.grimo.shared.workspace.WorkspaceManager;
import io.github.samzhu.grimo.skill.loader.SkillLoader;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import io.github.samzhu.grimo.task.scheduler.TaskSchedulerService;
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

    /** AI 對話併發控制：確保同時只有一個 agent 執行 */
    private volatile boolean agentRunning = false;
    private volatile Thread agentThread = null;

    // TUI 元件（run 時初始化）
    private GrimoContentView contentView;
    private GrimoInputView inputView;
    private GrimoSlashMenuView slashMenuView;
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
                           CommandRegistry commandRegistry) {
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

        screen = new GrimoScreen(terminal, contentView, inputView, statusView, slashMenuView);

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
            if (screen.isSlashMenuVisible()) {
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
     * 一般模式的鍵盤處理。
     */
    private void handleNormalKey(String operation, String lastBinding) {
        switch (operation) {
            case GrimoEventLoop.OP_ENTER -> {
                String text = inputView.getText().trim();
                if (!text.isEmpty()) {
                    history.add(text);
                    historyIndex = history.size();
                    savedInput = "";
                    contentView.appendUserInput(text);
                    inputView.clear();
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
                    agentThread.interrupt();
                    contentView.appendError("Agent cancelled.");
                    eventLoop.setDirty();
                } else {
                    inputView.clear();
                    historyIndex = history.size();
                    savedInput = "";
                }
            }
            case GrimoEventLoop.OP_BACKSPACE -> inputView.deleteChar();
            case GrimoEventLoop.OP_DELETE -> inputView.deleteForward();
            case GrimoEventLoop.OP_LEFT -> inputView.moveCursorLeft();
            case GrimoEventLoop.OP_RIGHT -> inputView.moveCursorRight();
            case GrimoEventLoop.OP_CHAR -> {
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
                log.info("Routing to agent, goal: {}", text.length() > 100 ? text.substring(0, 100) + "..." : text);
                agentRunning = true;
                contentView.appendLine(new org.jline.utils.AttributedString("\u23f3 thinking...",
                        org.jline.utils.AttributedStyle.DEFAULT.foreground(245)));
                eventLoop.setDirty();

                agentThread = Thread.startVirtualThread(() -> {
                    long startTime = System.currentTimeMillis();
                    try {
                        var client = org.springaicommunity.agents.client.AgentClient.create(model);
                        var response = client
                                .goal(text)
                                .workingDirectory(java.nio.file.Path.of(System.getProperty("user.dir")))
                                .run();

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
                        long duration = System.currentTimeMillis() - startTime;
                        log.error("Agent call failed: duration={}ms, error={}", duration, e.getMessage(), e);
                        String errorMsg = formatAgentError(e);
                        contentView.appendError(errorMsg);
                    } finally {
                        agentRunning = false;
                        agentThread = null;
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
