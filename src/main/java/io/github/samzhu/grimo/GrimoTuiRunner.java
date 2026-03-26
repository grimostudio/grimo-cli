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
import java.util.Map;

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
    private final AgentDetector agentDetector;
    private final SkillLoader skillLoader;
    private final SkillRegistry skillRegistry;
    private final TaskSchedulerService taskSchedulerService;
    private final McpClientManager mcpClientManager;
    private final McpClientRegistry mcpClientRegistry;
    private final BannerRenderer bannerRenderer;
    private final CommandParser commandParser;
    private final CommandExecutor commandExecutor;
    private final CommandRegistry commandRegistry;

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
                           AgentDetector agentDetector,
                           SkillLoader skillLoader,
                           SkillRegistry skillRegistry,
                           TaskSchedulerService taskSchedulerService,
                           McpClientManager mcpClientManager,
                           McpClientRegistry mcpClientRegistry,
                           BannerRenderer bannerRenderer,
                           CommandParser commandParser,
                           CommandExecutor commandExecutor,
                           CommandRegistry commandRegistry) {
        this.terminal = terminal;
        this.workspaceManager = workspaceManager;
        this.grimoConfig = grimoConfig;
        this.agentDetector = agentDetector;
        this.skillLoader = skillLoader;
        this.skillRegistry = skillRegistry;
        this.taskSchedulerService = taskSchedulerService;
        this.mcpClientManager = mcpClientManager;
        this.mcpClientRegistry = mcpClientRegistry;
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
        List<DetectionResult> agentResults = detectAgents();
        loadSkills();
        connectMcp();
        restoreTasks();

        // === Phase 3: 準備環境資訊 ===
        String version = getClass().getPackage().getImplementationVersion();
        if (version == null) version = "dev";
        String agentId = resolveAgentId(agentResults);
        String model = grimoConfig.getDefaultModel();
        if (model == null) model = "unknown";
        String workspacePath = workspaceManager.root().toString()
                .replace(System.getProperty("user.home"), "~");
        long agentCount = agentResults.stream().filter(DetectionResult::available).count();
        int skillCount = skillRegistry.listAll().size();
        int mcpCount = mcpClientRegistry.listAll().size();
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
                inputView.clear();
                historyIndex = history.size();
                savedInput = "";
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
            for (char c : lastBinding.toCharArray()) {
                if (c >= 32 && c < 127) {
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
            // 一般文字：AI 對話（Phase 2+ 實作）
            String reply = "（對話功能尚在開發中）";
            contentView.appendAiReply(reply);
            sessionWriter.writeAssistantMessage(reply);
        }
    }

    // === 載入步驟方法（靜默執行，無動畫）===

    private List<DetectionResult> detectAgents() {
        try {
            return agentDetector.detect();
        } catch (Exception e) {
            log.warn("Agent detection failed: {}", e.getMessage());
            return List.of();
        }
    }

    private void loadSkills() {
        try {
            var skills = skillLoader.loadAll();
            skills.forEach(skillRegistry::register);
        } catch (Exception e) {
            log.warn("Skill loading failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void connectMcp() {
        try {
            var config = grimoConfig.load();
            if (config.containsKey("mcp")) {
                var mcpServers = (Map<String, Map<String, String>>) config.get("mcp");
                for (var entry : mcpServers.entrySet()) {
                    try {
                        String transport = entry.getValue().get("transport");
                        String command = entry.getValue().get("command");
                        if ("stdio".equals(transport)) {
                            mcpClientManager.addStdio(entry.getKey(), command);
                        }
                    } catch (Exception e) {
                        log.warn("MCP server {} connection failed: {}", entry.getKey(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("MCP config loading failed: {}", e.getMessage());
        }
    }

    private void restoreTasks() {
        try {
            taskSchedulerService.restoreAll();
        } catch (Exception e) {
            log.warn("Task restoration failed: {}", e.getMessage());
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
