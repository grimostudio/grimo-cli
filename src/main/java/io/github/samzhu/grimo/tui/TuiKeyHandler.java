package io.github.samzhu.grimo.tui;

import io.github.samzhu.grimo.agent.AgentCommands;
import io.github.samzhu.grimo.config.GrimoConfig;
import io.github.samzhu.grimo.mcp.McpCatalogBuilder;
import io.github.samzhu.grimo.shared.session.SessionWriter;
import io.github.samzhu.grimo.tui.core.Renderable;
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
import io.github.samzhu.grimo.tui.widget.GroupedSelect;
import io.github.samzhu.grimo.tui.widget.ListSelect;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.CommandExecutor;
import org.springframework.shell.core.command.CommandParser;
import org.springframework.shell.core.command.CommandRegistry;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;

/**
 * TUI 鍵盤與滑鼠事件處理器：實作 EventLoop.KeyHandler。
 *
 * 設計說明：
 * - 從 TuiAdapter 的 inner class 提取為獨立頂層類別（SP4 Task 2）。
 * - 分三個模式：MCP Manager overlay、斜線選單（slashMenu）、一般輸入。
 * - 文字選取：active 時 Ctrl+C 複製到剪貼簿，其他鍵取消選取。
 * - 滑鼠滾輪直接轉為 ContentView 捲動；Pressed/Dragged/Released 處理文字選取。
 * - 透過 {@link InputCallback} 解耦業務邏輯（processInput），不直接耦合 TuiAdapter。
 * - 所有狀態更新後由 eventLoop.setDirty() 觸發重繪。
 */
public class TuiKeyHandler implements EventLoop.KeyHandler {

    private static final Logger log = LoggerFactory.getLogger(TuiKeyHandler.class);

    /**
     * 業務邏輯回呼：讓 TuiKeyHandler 解耦 TuiAdapter 的核心業務。
     *
     * 設計說明：
     * - 僅暴露 TuiKeyHandler 需要觸發的業務操作。
     * - TuiAdapter 以 method reference / lambda 實作各方法。
     */
    public interface InputCallback {
        /**
         * 使用者按 Enter 提交文字（命令或對話）。
         * TuiAdapter.processInput() 透過此回呼呼叫。
         *
         * @param text 使用者輸入的完整字串（已 trim）
         */
        void onTextSubmit(String text);
    }

    // --- 依賴注入的 TUI 元件 ---

    private final Terminal terminal;
    private final Screen screen;
    private final ContentView contentView;
    private final InputView inputView;
    private final StatusView statusView;
    private final SlashMenu slashMenu;
    private final McpPanel mcpPanel;
    private final TextSelection textSelection;
    private final Clipboard clipboard;
    /**
     * autoScroller 在 TuiKeyHandler 建立後才初始化（因為 AutoScroller 需要 eventLoop reference）。
     * 透過 {@link #setAutoScroller} 注入。
     */
    private AutoScroller autoScroller;
    /**
     * eventLoop 在 TuiKeyHandler 建立後才初始化（因為 EventLoop 需要 TuiKeyHandler reference）。
     * 透過 {@link #setEventLoop} 注入。
     */
    private EventLoop eventLoop;

    // --- 業務邏輯元件（用於 select overlay 的指令執行）---

    private final GrimoConfig grimoConfig;
    private final McpCatalogBuilder mcpCatalogBuilder;
    private final SessionWriter sessionWriter;
    private final CommandParser commandParser;
    private final CommandExecutor commandExecutor;
    private final CommandRegistry commandRegistry;

    // --- 回呼（解耦 processInput 等業務邏輯）---

    private final InputCallback inputCallback;

    // --- 可變狀態 ---

    /** 輸入歷史（與 TuiAdapter 共用 list reference） */
    private final List<String> history;
    private int historyIndex;
    private String savedInput;

    /** Agent 執行中旗標與執行緒 reference（從 TuiAdapter 傳入的 wrapper，以 volatile 欄位確保可見性） */
    private final AgentStateRef agentState;

    /** Double Ctrl+C 退出計時 */
    private long lastCtrlCTime = 0;
    private static final long CTRL_C_EXIT_WINDOW_MS = 2000;

    /** 目前顯示在 overlay 的 select 元件 */
    private Renderable activeSelectOverlay;

    /**
     * Mutable wrapper，讓 TuiKeyHandler 可讀寫 TuiAdapter 的 agentRunning / agentThread。
     *
     * 設計說明：
     * - agentRunning 和 agentThread 是 TuiAdapter 的業務狀態，不適合複製。
     * - 透過此 wrapper，TuiKeyHandler 在 Ctrl+C 取消 agent 時可中斷 agentThread。
     */
    public static class AgentStateRef {
        public volatile boolean agentRunning = false;
        public volatile Thread agentThread = null;
    }

    public TuiKeyHandler(
            Terminal terminal,
            Screen screen,
            ContentView contentView,
            InputView inputView,
            StatusView statusView,
            SlashMenu slashMenu,
            McpPanel mcpPanel,
            TextSelection textSelection,
            Clipboard clipboard,
            GrimoConfig grimoConfig,
            McpCatalogBuilder mcpCatalogBuilder,
            SessionWriter sessionWriter,
            CommandParser commandParser,
            CommandExecutor commandExecutor,
            CommandRegistry commandRegistry,
            InputCallback inputCallback,
            List<String> history,
            int historyIndex,
            String savedInput,
            AgentStateRef agentState) {
        this.terminal = terminal;
        this.screen = screen;
        this.contentView = contentView;
        this.inputView = inputView;
        this.statusView = statusView;
        this.slashMenu = slashMenu;
        this.mcpPanel = mcpPanel;
        this.textSelection = textSelection;
        this.clipboard = clipboard;
        this.grimoConfig = grimoConfig;
        this.mcpCatalogBuilder = mcpCatalogBuilder;
        this.sessionWriter = sessionWriter;
        this.commandParser = commandParser;
        this.commandExecutor = commandExecutor;
        this.commandRegistry = commandRegistry;
        this.inputCallback = inputCallback;
        this.history = history;
        this.historyIndex = historyIndex;
        this.savedInput = savedInput;
        this.agentState = agentState;
    }

    // --- 延遲注入 setter（解決 eventLoop ↔ TuiKeyHandler 循環依賴） ---

    /**
     * 注入 EventLoop reference（在 EventLoop 建立後呼叫）。
     * 設計說明：EventLoop 建構時需要 TuiKeyHandler，TuiKeyHandler 使用時需要 EventLoop，
     * 透過此 setter 打破循環依賴。
     */
    public void setEventLoop(EventLoop eventLoop) {
        this.eventLoop = eventLoop;
    }

    /**
     * 注入 AutoScroller reference（在 AutoScroller 建立後呼叫）。
     * 設計說明：AutoScroller 需要 EventLoop，而 EventLoop 需要 TuiKeyHandler，
     * 透過此 setter 打破循環依賴。
     */
    public void setAutoScroller(AutoScroller autoScroller) {
        this.autoScroller = autoScroller;
    }

    // --- EventLoop.KeyHandler 實作 ---

    @Override
    public void handleKey(String operation, String lastBinding) {
        // Ctrl+C 有選取時 → 複製到剪貼簿（參考 Claude Code 行為）
        // 設計說明：macOS raw mode 下，Ctrl+C 可能被 BindingReader 當成 CHAR（\003），
        // 而非匹配到 OP_CTRL_C binding。同時檢查 operation 和 lastBinding 確保捕捉到。
        log.info("[KEY] op={} lastBinding={} selectionActive={}",
                operation, lastBinding != null ? String.format("0x%02x", (int) lastBinding.charAt(0)) : "null",
                textSelection.isActive());
        boolean isCtrlC = EventLoop.OP_CTRL_C.equals(operation)
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
                        clipboard.copy(terminal, text);
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
        } else if (screen.hasSelectOverlay()) {
            handleSelectOverlayInput(operation);
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
                            clipboard.copy(terminal, text);
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

    // --- 各模式鍵盤處理 ---

    /**
     * 斜線指令選單模式的鍵盤處理。
     */
    private void handleSlashMenuKey(String operation, String lastBinding) {
        switch (operation) {
            case EventLoop.OP_UP -> slashMenu.moveUp();
            case EventLoop.OP_DOWN -> slashMenu.moveDown();
            case EventLoop.OP_TAB, EventLoop.OP_ENTER -> {
                String selected = slashMenu.getSelected();
                if (selected != null) {
                    inputView.insertSlashCommand(selected);
                }
                screen.setSlashMenuVisible(false);
            }
            case EventLoop.OP_BACKSPACE -> {
                inputView.deleteChar();
                String slashToken = inputView.getCurrentSlashToken();
                if (slashToken == null || !slashMenu.hasItems()) {
                    screen.setSlashMenuVisible(false);
                } else {
                    slashMenu.filter(slashToken.substring(1));
                    if (!slashMenu.hasItems()) {
                        screen.setSlashMenuVisible(false);
                    }
                }
            }
            case EventLoop.OP_CTRL_C -> screen.setSlashMenuVisible(false);
            case EventLoop.OP_CHAR -> {
                insertCharFromBinding(lastBinding);
                String slashToken = inputView.getCurrentSlashToken();
                if (slashToken != null) {
                    slashMenu.filter(slashToken.substring(1));
                    if (!slashMenu.hasItems()) {
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
            case EventLoop.OP_UP -> mcpPanel.moveUp();
            case EventLoop.OP_DOWN -> mcpPanel.moveDown();
            case EventLoop.OP_ESC, EventLoop.OP_CTRL_C -> {
                screen.setMcpManagerVisible(false);
                screen.requestFullRedraw();
            }
            case EventLoop.OP_CHAR -> {
                if (lastBinding != null && lastBinding.length() == 1) {
                    char c = lastBinding.charAt(0);
                    if (c == 'd' || c == 'D') {
                        String name = mcpPanel.getSelectedName();
                        if (name != null) {
                            grimoConfig.removeMcpServer(name);
                            mcpCatalogBuilder.rebuild();
                            mcpPanel.load(grimoConfig.getMcpServers());
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
            case EventLoop.OP_ENTER -> {
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
                    inputCallback.onTextSubmit(text);
                }
            }
            case EventLoop.OP_UP, EventLoop.OP_DOWN -> {
                // ↑/↓ 在一般模式暫不處理（未來可加歷史瀏覽）
            }
            case EventLoop.OP_CTRL_U -> {
                int halfPage = Math.max(1, (terminal.getHeight() - 4) / 2);
                contentView.scrollUp(halfPage);
            }
            case EventLoop.OP_CTRL_D -> {
                int halfPage = Math.max(1, (terminal.getHeight() - 4) / 2);
                contentView.scrollDown(halfPage);
            }
            case EventLoop.OP_CTRL_C -> {
                if (agentState.agentRunning && agentState.agentThread != null) {
                    // Agent 執行中 → 取消 agent
                    agentState.agentThread.interrupt();
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
            case EventLoop.OP_BACKSPACE -> {
                inputView.deleteChar();
                // backspace 後檢查是否該重開斜線選單
                // 修正：選單因過濾為空而關閉後，backspace 回到有效 token 時應重開
                tryReopenSlashMenu();
            }
            case EventLoop.OP_DELETE -> inputView.deleteForward();
            case EventLoop.OP_LEFT -> inputView.moveCursorLeft();
            case EventLoop.OP_RIGHT -> inputView.moveCursorRight();
            case EventLoop.OP_CHAR -> {
                log.debug("OP_CHAR, lastBinding bytes={}, text='{}'",
                        lastBinding != null ? java.util.HexFormat.of().formatHex(lastBinding.getBytes()) : "null",
                        lastBinding);
                insertCharFromBinding(lastBinding);
                if (inputView.shouldOpenSlashMenu()) {
                    slashMenu.filterAll();
                    screen.setSlashMenuVisible(true);
                }
            }
        }
    }

    /**
     * 處理 select overlay（GroupedSelect / ListSelect）的鍵盤輸入。
     *
     * 設計說明：
     * - GroupedSelect：Enter on group header → toggle；Enter on leaf → 執行 agent-use 指令
     * - ListSelect：Enter / Esc 關閉 overlay
     * - Esc / Ctrl+C：關閉 overlay
     */
    private void handleSelectOverlayInput(String operation) {
        if (activeSelectOverlay instanceof GroupedSelect<?> grouped) {
            @SuppressWarnings("unchecked")
            var typedGrouped = (GroupedSelect<String>) grouped;
            switch (operation) {
                case EventLoop.OP_UP -> typedGrouped.moveUp();
                case EventLoop.OP_DOWN -> typedGrouped.moveDown();
                case EventLoop.OP_ENTER -> {
                    if (typedGrouped.isOnGroup()) {
                        typedGrouped.toggle();
                    } else {
                        var selected = typedGrouped.getSelected();
                        screen.clearSelectOverlay();
                        activeSelectOverlay = null;
                        if (selected != null) {
                            // 透過統一管線執行：callback → processInput → IncomingMessageEvent → MessageRouter
                            inputCallback.onTextSubmit("/agent-use " + selected.value());
                        }
                    }
                }
                case EventLoop.OP_ESC, EventLoop.OP_CTRL_C -> {
                    screen.clearSelectOverlay();
                    activeSelectOverlay = null;
                }
            }
        } else if (activeSelectOverlay instanceof ListSelect<?> list) {
            @SuppressWarnings("unchecked")
            var typedList = (ListSelect<Object>) list;
            switch (operation) {
                case EventLoop.OP_UP -> typedList.moveUp();
                case EventLoop.OP_DOWN -> typedList.moveDown();
                case EventLoop.OP_ENTER -> {
                    screen.clearSelectOverlay();
                    activeSelectOverlay = null;
                }
                case EventLoop.OP_ESC, EventLoop.OP_CTRL_C -> {
                    screen.clearSelectOverlay();
                    activeSelectOverlay = null;
                }
            }
        }
        eventLoop.setDirty();
    }

    // --- 輔助方法 ---

    /**
     * 捲動滾輪事件。
     */
    private void handleWheel(MouseEvent event) {
        if (event.getButton() == MouseEvent.Button.WheelUp) {
            contentView.scrollUp(3);
        } else if (event.getButton() == MouseEvent.Button.WheelDown) {
            contentView.scrollDown(3);
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
            slashMenu.filter(slashToken.substring(1));
            if (slashMenu.hasItems()) {
                screen.setSlashMenuVisible(true);
            }
        }
    }

    /**
     * 開啟 MCP Manager overlay。
     * 互斥保證：先關閉 slash menu 再開啟 MCP Manager。
     */
    public void openMcpManager() {
        screen.setSlashMenuVisible(false);
        mcpPanel.load(grimoConfig.getMcpServers());
        screen.setMcpManagerVisible(true);
    }

    /**
     * 設定目前顯示的 select overlay 元件（由 TuiAdapter 在 showAgentPicker 後呼叫）。
     */
    public void setActiveSelectOverlay(Renderable overlay) {
        this.activeSelectOverlay = overlay;
    }

    /**
     * 取得目前顯示的 select overlay 元件。
     */
    public Renderable getActiveSelectOverlay() {
        return activeSelectOverlay;
    }
}
