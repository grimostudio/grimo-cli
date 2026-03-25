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
import org.springframework.shell.jline.tui.component.view.TerminalUI;
import org.springframework.shell.jline.tui.component.view.control.DialogView;
import org.springframework.shell.jline.tui.component.view.control.GridView;
import org.springframework.shell.jline.tui.component.view.control.StatusBarView;
import org.springframework.shell.jline.tui.component.view.control.StatusBarView.StatusItem;
import org.springframework.shell.jline.tui.component.view.event.KeyEvent.Key;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自建 TUI Runner：使用 Spring Shell TerminalUI 框架建構完整 TUI 介面。
 *
 * 設計說明：
 * - 取代舊版 JLine LineReader + 手動 ANSI scroll region 的做法
 * - 使用 GridView 三區佈局：content(flex) + input(1行) + status(1行)
 * - GrimoContentView 負責 banner 顯示和對話紀錄（底部對齊 + 滾動）
 * - GrimoInputView 自建輸入元件（InputView 缺少 setText()）
 * - StatusBarView 顯示 agent/model/workspace/計數資訊
 * - TerminalUI.run() 阻塞式事件迴圈，取代 shellRunner.run()
 *
 * @see <a href="https://docs.spring.io/spring-shell/reference/tui/intro/terminalui.html">TerminalUI :: Spring Shell</a>
 * @see <a href="https://docs.spring.io/spring-shell/reference/tui/views/grid.html">GridView :: Spring Shell</a>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GrimoTuiRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GrimoTuiRunner.class);

    /** 輸入歷史紀錄（自建，因不使用 JLine LineReader） */
    private final List<String> history = new ArrayList<>();
    private int historyIndex = 0;
    /** 歷史瀏覽前暫存目前輸入，離開歷史模式時恢復 */
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

        // === Phase 4: 建構 TerminalUI ===
        TerminalUI ui = new TerminalUI(terminal);

        // Content 區：banner + 對話紀錄
        var contentView = new GrimoContentView();
        String bannerText = bannerRenderer.render(
                version, agentId, model, workspacePath,
                (int) agentCount, skillCount, mcpCount, taskCount);
        contentView.setBannerText(bannerText);

        // Input 區：自建輸入元件
        var inputView = new GrimoInputView();

        // Status 區
        String statusText = agentId + " · " + model + " │ " + workspacePath
                + " │ " + (int) agentCount + " agent · " + skillCount + " skill · "
                + mcpCount + " mcp · " + taskCount + " task";
        var statusBar = new StatusBarView(List.of(StatusItem.of(statusText)));

        // 三區 GridView：content(flex) + input(3行: ─+❯+─) + status(1行)
        var root = new GridView();
        root.setColumnSize(0);     // 1 欄 flex（佔滿終端寬度）
        root.setRowSize(0, 3, 1);  // row 0=flex, row 1=3行(separator+input+separator), row 2=1行
        root.addItem(contentView, 0, 0, 1, 1, 0, 0);
        root.addItem(inputView, 1, 0, 1, 1, 0, 0);
        root.addItem(statusBar, 2, 0, 1, 1, 0, 0);

        // 斜線指令選單：從 CommandRegistry + SkillRegistry 建構候選項
        var menuItems = buildMenuItems();
        var slashCommandListView = new GrimoSlashCommandListView(menuItems);
        var slashCommandDialog = new DialogView(slashCommandListView);

        ui.configure(contentView);
        ui.configure(inputView);
        ui.configure(statusBar);
        ui.configure(slashCommandListView);
        ui.setRoot(root, true);  // fullscreen 模式

        // === Session 對話存檔 ===
        String cwd = System.getProperty("user.dir");
        String encodedCwd = cwd.replaceAll("[^a-zA-Z0-9]", "-");
        var sessionsDir = workspaceManager.root().resolve("projects").resolve(encodedCwd).resolve("sessions");
        var sessionWriter = new SessionWriter(sessionsDir);
        sessionWriter.writeSystemMessage(cwd, version, "Grimo TUI session");

        // === Phase 5: 註冊鍵盤事件處理（含斜線選單） ===
        registerKeyHandlers(ui, inputView, contentView, slashCommandListView, slashCommandDialog, sessionWriter);

        log.debug("Grimo TUI setup complete, starting TerminalUI event loop.");

        // === Phase 6: 啟動 TUI 事件迴圈（阻塞） ===
        ui.run();
    }

    /**
     * 註冊鍵盤事件處理器。
     *
     * 設計說明：
     * - 斜線指令選單模式（modal 可見）：↑↓ 選擇、Tab/Enter 填入、Esc 關閉
     * - 一般模式：字元輸入、Enter 送出、Ctrl+C 清空、Ctrl+D 退出
     * - 輸入「行首/」或「空格+/」時自動開啟斜線指令選單
     */
    private void registerKeyHandlers(TerminalUI ui, GrimoInputView inputView,
                                      GrimoContentView contentView,
                                      GrimoSlashCommandListView slashCommandListView,
                                      DialogView slashCommandDialog,
                                      SessionWriter sessionWriter) {
        var eventLoop = ui.getEventLoop();

        eventLoop.keyEvents().subscribe(event -> {
            // 過濾 mouse SGR escape sequences（TerminalUI 內部啟用 mouse tracking，
            // 但無法正確解析 scroll wheel 事件，raw sequences 漏到 keyEvents）
            String data = event.data();
            if (data != null && data.contains("[<")) return;

            if (ui.getModal() != null) {
                // === 斜線指令選單模式（modal 可見）===
                handleSlashMenuKey(event, ui, inputView, slashCommandListView, slashCommandDialog);
            } else {
                // === 一般模式 ===
                handleNormalKey(event, ui, inputView, contentView, slashCommandListView, slashCommandDialog, sessionWriter);
            }
            ui.redraw();
        });
    }

    /**
     * 斜線指令選單模式的鍵盤處理。
     */
    private void handleSlashMenuKey(org.springframework.shell.jline.tui.component.view.event.KeyEvent event,
                                     TerminalUI ui, GrimoInputView inputView,
                                     GrimoSlashCommandListView slashCommandListView,
                                     DialogView slashCommandDialog) {
        if (event.isKey(Key.CursorUp)) {
            slashCommandListView.moveUp();
        } else if (event.isKey(Key.CursorDown)) {
            slashCommandListView.moveDown();
        } else if (event.isKey(Key.Tab) || event.isKey(Key.Enter)) {
            // 填入選中斜線指令到 input
            String selected = slashCommandListView.getSelected();
            if (selected != null) {
                inputView.insertSlashCommand(selected);
            }
            ui.setModal(null);
        } else if (event.isKey(Key.Backspace)) {
            inputView.deleteChar();
            String slashToken = inputView.getCurrentSlashToken();
            if (slashToken == null) {
                ui.setModal(null);
            } else {
                slashCommandListView.filter(slashToken.substring(1));
                if (!slashCommandListView.hasItems()) {
                    ui.setModal(null);
                }
            }
        } else if (event.hasCtrl() && event.getPlainKey() == Key.c) {
            // Ctrl+C 在選單模式也關閉選單
            ui.setModal(null);
        } else {
            // 普通字元：插入字元並更新過濾
            insertCharFromEvent(event, inputView);
            String slashToken = inputView.getCurrentSlashToken();
            if (slashToken != null) {
                slashCommandListView.filter(slashToken.substring(1));
                if (!slashCommandListView.hasItems()) {
                    ui.setModal(null);
                }
            } else {
                ui.setModal(null);
            }
        }
    }

    /**
     * 一般模式的鍵盤處理（含 ↑↓ 歷史瀏覽）。
     *
     * 設計說明：
     * - ↑ 鍵：瀏覽前一筆歷史（首次按下時暫存目前輸入）
     * - ↓ 鍵：瀏覽下一筆歷史（到最底恢復暫存的輸入）
     * - Enter 時加入 history
     */
    private void handleNormalKey(org.springframework.shell.jline.tui.component.view.event.KeyEvent event,
                                  TerminalUI ui, GrimoInputView inputView,
                                  GrimoContentView contentView,
                                  GrimoSlashCommandListView slashCommandListView,
                                  DialogView slashCommandDialog,
                                  SessionWriter sessionWriter) {
        if (event.isKey(Key.Enter)) {
            String text = inputView.getText().trim();
            if (!text.isEmpty()) {
                history.add(text);
                historyIndex = history.size();
                savedInput = "";
                contentView.appendUserInput(text);
                inputView.clear();
                sessionWriter.writeUserMessage(text);
                processInput(text, contentView, ui, sessionWriter);
            }
        } else if (event.isKey(Key.CursorUp) || event.isKey(Key.CursorDown)) {
            // ↑/↓ 在一般模式不處理（僅在斜線指令選單模式用於選擇指令）
        } else if (event.hasCtrl() && event.getPlainKey() == Key.c) {
            inputView.clear();
            historyIndex = history.size();
            savedInput = "";
        } else if (event.hasCtrl() && event.getPlainKey() == Key.d) {
            System.exit(0);
        } else if (event.isKey(Key.Backspace)) {
            inputView.deleteChar();
        } else if (event.isKey(Key.Delete)) {
            inputView.deleteForward();
        } else if (event.isKey(Key.CursorLeft)) {
            inputView.moveCursorLeft();
        } else if (event.isKey(Key.CursorRight)) {
            inputView.moveCursorRight();
        } else {
            // 普通字元輸入
            insertCharFromEvent(event, inputView);
            // 偵測是否應開啟斜線指令選單
            if (inputView.shouldOpenSlashMenu()) {
                slashCommandListView.filterAll();
                int h = terminal.getHeight();
                int w = terminal.getWidth();
                int menuHeight = slashCommandListView.getVisibleCount();
                slashCommandDialog.setRect(0, h - 4 - menuHeight, w, menuHeight);
                ui.setModal(slashCommandDialog);
            }
        }
    }

    /**
     * 從 KeyEvent 插入字元到 inputView。
     */
    private void insertCharFromEvent(org.springframework.shell.jline.tui.component.view.event.KeyEvent event,
                                      GrimoInputView inputView) {
        if (event.isKey() && event.data() != null && !event.data().isEmpty()) {
            for (char c : event.data().toCharArray()) {
                inputView.insertChar(c);
            }
        } else {
            int key = event.key();
            if (key >= 32 && key < 127) {
                inputView.insertChar((char) key);
            }
        }
    }

    /**
     * 從 CommandRegistry + SkillRegistry 建構斜線指令選單項目。
     */
    private List<GrimoSlashCommandListView.MenuItem> buildMenuItems() {
        var items = new java.util.ArrayList<GrimoSlashCommandListView.MenuItem>();

        // 從 CommandRegistry 取得所有命令（排除 chat）
        commandRegistry.getCommandsByPrefix("").stream()
                .filter(cmd -> !"chat".equals(cmd.getName()))
                .forEach(cmd -> items.add(new GrimoSlashCommandListView.MenuItem(
                        cmd.getName(), cmd.getDescription())));

        // 從 SkillRegistry 取得所有 Skills
        skillRegistry.listAll().forEach(skill ->
                items.add(new GrimoSlashCommandListView.MenuItem(
                        skill.name(), skill.description())));

        return items;
    }

    /**
     * 處理使用者輸入：判斷是斜線指令還是對話。
     *
     * 設計說明：
     * - /exit 直接退出
     * - 以 / 開頭 → CommandParser.parse() → CommandContext → CommandExecutor.execute()
     * - 命令輸出透過 StringWriter 捕獲，轉發到 contentView
     * - 其他文字 → AI 對話（Phase 2+ 完整實作）
     */
    private void processInput(String text, GrimoContentView contentView, TerminalUI ui,
                               SessionWriter sessionWriter) {
        if (text.equals("/exit")) {
            System.exit(0);
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
        ui.redraw();
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
