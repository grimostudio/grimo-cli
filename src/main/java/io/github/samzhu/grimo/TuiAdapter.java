package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.tier.TierRouter;
import io.github.samzhu.grimo.command.InputMetadata;
import io.github.samzhu.grimo.command.InputPort;
import io.github.samzhu.grimo.config.GrimoConfig;
import io.github.samzhu.grimo.config.GrimoProperties;
import io.github.samzhu.grimo.home.GrimoHome;
import io.github.samzhu.grimo.mcp.McpCatalogBuilder;
import io.github.samzhu.grimo.project.ProjectContext;
import io.github.samzhu.grimo.shared.sandbox.GitHelper;
import io.github.samzhu.grimo.shared.sandbox.WorktreeProvisioner;
import io.github.samzhu.grimo.shared.session.SessionManager;
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
import org.springframework.stereotype.Component;

import io.github.samzhu.grimo.shared.session.SessionIndex;
import io.github.samzhu.grimo.tui.TuiKeyHandler;
import io.github.samzhu.grimo.tui.overlay.McpPanel;
import io.github.samzhu.grimo.tui.overlay.SessionPickerOverlay;
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
import io.github.samzhu.grimo.tui.widget.ReactionIndicator;

import java.time.Duration;
import java.time.Instant;
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
 * - 命令處理由 InputPort → InputHandler → CommandDispatcher 管線負責
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
    private final McpCatalogBuilder mcpCatalogBuilder;
    private final WorktreeProvisioner worktreeProvisioner;
    private final GitHelper gitHelper;
    private final TuiEventBridge tuiEventBridge;
    private final ChatDispatcher chatDispatcher;
    private final InputPort inputPort;
    private final GrimoProperties grimoProperties;
    private final TierRouter tierRouter;
    private final AtomicReference<String> startupFallbackMessage;

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
    private final SessionManager sessionManager;
    private TextSelection textSelection;
    private AutoScroller autoScroller;
    private Clipboard clipboard;

    /** 鍵盤/滑鼠事件處理器（run 時初始化） */
    private TuiKeyHandler tuiKeyHandler;

    public TuiAdapter(Terminal terminal,
                           GrimoHome grimoHome,
                           ProjectContext projectContext,
                           SessionManager sessionManager,
                           GrimoConfig grimoConfig,
                           GrimoProperties grimoProperties,
                           AgentModelRegistry agentModelRegistry,
                           SkillRegistry skillRegistry,
                           TaskSchedulerService taskSchedulerService,
                           Banner banner,
                           McpCatalogBuilder mcpCatalogBuilder,
                           WorktreeProvisioner worktreeProvisioner,
                           GitHelper gitHelper,
                           TuiEventBridge tuiEventBridge,
                           ChatDispatcher chatDispatcher,
                           InputPort inputPort,
                           TierRouter tierRouter,
                           AtomicReference<String> startupFallbackMessage) {
        this.terminal = terminal;
        this.grimoHome = grimoHome;
        this.projectContext = projectContext;
        this.sessionManager = sessionManager;
        this.grimoConfig = grimoConfig;
        this.grimoProperties = grimoProperties;
        this.agentModelRegistry = agentModelRegistry;
        this.skillRegistry = skillRegistry;
        this.taskSchedulerService = taskSchedulerService;
        this.banner = banner;
        this.mcpCatalogBuilder = mcpCatalogBuilder;
        this.worktreeProvisioner = worktreeProvisioner;
        this.gitHelper = gitHelper;
        this.tuiEventBridge = tuiEventBridge;
        this.chatDispatcher = chatDispatcher;
        this.inputPort = inputPort;
        this.tierRouter = tierRouter;
        this.startupFallbackMessage = startupFallbackMessage;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 設計說明：Phase 1（Home/Project 初始化）與 Phase 2（Agent 偵測、Skill 載入、MCP、Task、Sandbox）
        // 已移至 GrimoStartupRunner.startupInitRunner（Order HIGHEST_PRECEDENCE + 1，先執行），
        // 本 runner（Order HIGHEST_PRECEDENCE + 2，後執行）在初始化完成後建構 TUI。

        // === 準備環境資訊 ===
        String version = getClass().getPackage().getImplementationVersion();
        if (version == null) version = "dev";
        String agentId;
        String model;
        try {
            var selection = tierRouter.resolveDefault();
            agentId = selection.agentId();
            model = selection.model();
        } catch (IllegalStateException e) {
            agentId = "no agent";
            model = "unknown";
        }
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

        // 顯示啟動時的 agent fallback 提示訊息（若 default agent 不可用時自動切換）
        String fallbackMsg = startupFallbackMessage.get();
        if (fallbackMsg != null) {
            contentView.appendLine(new org.jline.utils.AttributedString(fallbackMsg,
                    org.jline.utils.AttributedStyle.DEFAULT.foreground(3))); // yellow
        }

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

        // === Session startup mode ===
        // 設計說明：--resume 優先於 --continue；default 建立新 session。
        // startNewSession 產生新 session ID、初始化 JSONL、寫入 system message、更新 index。
        String gitBranch = gitHelper.getCurrentBranch(projectContext.path());
        String cwd = projectContext.path().toString();

        boolean hasResume = args.containsOption("resume");
        boolean hasContinue = args.containsOption("continue");

        if (hasResume) {
            var resumeValues = args.getOptionValues("resume");
            if (resumeValues != null && !resumeValues.isEmpty() && !resumeValues.getFirst().isEmpty()) {
                // --resume <id>
                String id = resumeValues.getFirst();
                try {
                    sessionManager.resumeSession(id);
                } catch (IllegalArgumentException e) {
                    System.err.println("Session not found: " + id);
                    System.exit(1);
                }
            } else {
                // --resume (no id) → terminal picker before TUI
                var sessions = sessionManager.listSessions();
                if (sessions.isEmpty()) {
                    sessionManager.startNewSession(gitBranch, cwd, version);
                } else {
                    var selected = showTerminalSessionPicker(sessions);
                    if (selected != null) {
                        sessionManager.resumeSession(selected.sessionId());
                    } else {
                        sessionManager.startNewSession(gitBranch, cwd, version);
                    }
                }
            }
        } else if (hasContinue) {
            boolean resumed = sessionManager.continueLastSession();
            if (!resumed) {
                sessionManager.startNewSession(gitBranch, cwd, version);
            }
        } else {
            sessionManager.startNewSession(gitBranch, cwd, version);
        }

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
                grimoConfig, mcpCatalogBuilder, sessionManager,
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

        // Reaction Indicator（OpenClaw-style emoji state feedback）
        // 設計說明：ReactionIndicator 是 ContentView render 時動態加入的浮動行，
        // 由 TuiEventBridge 的 DispatchLifecycleEvent listeners 驅動狀態切換。
        var reactionIndicator = new ReactionIndicator(() -> {
            if (eventLoop != null) eventLoop.setDirty();
        });
        contentView.setReactionIndicator(reactionIndicator);

        // 繫結 TUI 元件到 TuiEventBridge（domain events → TUI 更新）
        tuiEventBridge.bind(statusView, contentView, () -> eventLoop.setDirty(), statusText, reactionIndicator);

        // 繫結 TUI 元件到 ChatDispatcher（AI 對話 dispatch 需要直接更新 TUI）
        chatDispatcher.bindTui(contentView, statusView, eventLoop, agentState);

        // === Startup session replay ===
        // 設計說明：resume/continue 啟動時，contentView 已建構完成，將歷史訊息 replay 到 TUI
        if (sessionManager.isResumed()) {
            var messages = sessionManager.getWriter().readAllMessages(
                    sessionManager.getWriter().getSessionFile());
            TuiEventBridge.replayMessages(messages, contentView);
        }

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

    /**
     * 顯示 session picker overlay（SessionPickerOverlay）。
     * /session-resume 無參數時觸發。
     * 設計說明：排除當前 session，列出可 resume 的歷史 session 供選擇。
     */
    private void showSessionPicker() {
        var entries = sessionManager.listSessions();
        // Exclude current session from picker
        var currentId = sessionManager.getCurrentInfo() != null
                ? sessionManager.getCurrentInfo().sessionId() : null;
        var filtered = entries.stream()
                .filter(e -> !e.sessionId().equals(currentId))
                .toList();

        var picker = new SessionPickerOverlay(filtered);
        tuiKeyHandler.setActiveSelectOverlay(picker);
        screen.setSelectOverlay(picker);
        eventLoop.setDirty();
    }

    /**
     * 啟動前（TUI 尚未建構）的 terminal session picker：以簡易數字列表讓使用者選擇。
     * --resume 無 ID 時觸發。
     */
    private SessionIndex.Entry showTerminalSessionPicker(List<SessionIndex.Entry> sessions) {
        System.out.println("? Select a session to resume:");
        for (int i = 0; i < sessions.size(); i++) {
            var e = sessions.get(i);
            String timeAgo = formatTimeAgo(e.lastActiveAt());
            String msg = e.firstUserMessage() != null ? e.firstUserMessage() : "";
            if (msg.length() > 50) msg = msg.substring(0, 50) + "...";
            System.out.printf("  %s%d) %s  %s  %d msgs  %s%n",
                    i == 0 ? "> " : "  ", i + 1, e.sessionId(), timeAgo, e.messageCount(), msg);
        }
        System.out.print("Enter number (or press Enter to cancel): ");
        try {
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            String line = reader.readLine();
            if (line != null && !line.isBlank()) {
                int idx = Integer.parseInt(line.trim()) - 1;
                if (idx >= 0 && idx < sessions.size()) return sessions.get(idx);
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    private String formatTimeAgo(Instant time) {
        if (time == null) return "";
        var d = Duration.between(time, Instant.now());
        if (d.toDays() > 0) return d.toDays() + "d ago";
        if (d.toHours() > 0) return d.toHours() + "h ago";
        if (d.toMinutes() > 0) return d.toMinutes() + "m ago";
        return "now";
    }

    private List<ListSelect.Item<String>> buildModelItems(String agentId) {
        // 設計說明：從 GrimoProperties.getModels() 讀取該 agent 所有可用模型
        // 模型清單由開發者在 application.yaml grimo.models 維護，不替使用者過濾
        var modelEntries = grimoProperties.getModels().get(agentId);
        if (modelEntries != null && !modelEntries.isEmpty()) {
            return modelEntries.stream()
                    .map(entry -> new ListSelect.Item<>(entry.id(), "", agentId + " " + entry.id()))
                    .toList();
        }
        // fallback：models 沒有該 agent 時，用 defaults
        String defaultModel = grimoProperties.getDefaults().getOrDefault(agentId, agentId);
        return List.of(new ListSelect.Item<>(defaultModel, "", agentId + " " + defaultModel));
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
        if (text.equals("/session-resume")) { showSessionPicker(); return; }

        // 設計說明：appendUserInput + writeUserMessage 已在 TuiKeyHandler.handleNormalKey() 中處理
        // （ENTER 按下時先 appendUserInput → clear input → 才呼叫 onTextSubmit → 到這裡）
        // 不重複呼叫，避免 "hi" 出現兩次。

        // 設計說明：thinking indicator 已移至 ReactionIndicator（由 DispatchLifecycleEvent 驅動），
        // 不再需要手動 appendLine("thinking...") + removeLastLine()。
        boolean isChat = !text.startsWith("/");

        // 六角架構：Adapter 直接呼叫 Port（不經 IncomingMessageEvent）
        log.debug("[PROCESS-INPUT] before handleInput: text='{}', isChat={}", text, isChat);
        var sessionInfo = sessionManager.getCurrentInfo();
        inputPort.handleInput(text,
                InputMetadata.tui(sessionInfo != null ? sessionInfo.sessionId() : null),
                new InputPort.ResponseCallback() {
                    @Override public void onSuccess(String result) {
                        log.info("[CALLBACK-SUCCESS] isChat={}, resultLen={}, resultPreview='{}'",
                                isChat, result != null ? result.length() : 0,
                                result != null ? result.substring(0, Math.min(200, result.length()))
                                        .replace("\n", "\\n") : "(null)");
                        if (isChat) {
                            // REMOVED: contentView.removeLastLine() — ReactionIndicator replaces manual thinking line
                            contentView.appendAiReply(result);
                        } else {
                            contentView.appendCommandOutput(result);
                        }
                        eventLoop.setDirty();
                    }
                    @Override public void onError(String message) {
                        log.info("[CALLBACK-ERROR] isChat={}, message='{}'", isChat, message);
                        // REMOVED: contentView.removeLastLine() — ReactionIndicator replaces manual thinking line
                        contentView.appendError(message);
                        eventLoop.setDirty();
                    }
                });
    }

}
