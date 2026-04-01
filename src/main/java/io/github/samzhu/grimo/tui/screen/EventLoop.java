package io.github.samzhu.grimo.tui.screen;

import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自建 TUI 事件迴圈：用 JLine BindingReader + Display 取代 Spring Shell TerminalUI。
 *
 * 設計說明：
 * - 雙執行緒模式（參考 JLine Tmux.java）：
 *   Thread 1 (Input)：BindingReader.readBinding() 阻塞等輸入，處理後 setDirty()
 *   Thread 2 (Render)：等 dirty 通知後呼叫 screen.render()（Display diff-based）
 * - Mouse binding 綁兩個前綴：\033[M (X10) + \033[< (SGR)，解決 Spring Shell 的 KeyMap bug
 * - AI streaming 或任何 async 資料更新後呼叫 setDirty() 即可觸發重繪
 *
 * @see <a href="https://github.com/jline/jline3/blob/master/builtins/src/main/java/org/jline/builtins/Tmux.java">JLine Tmux — 雙執行緒模式參考</a>
 * @see <a href="https://jline.org/docs/advanced/mouse-support/">JLine Mouse Support</a>
 */
public class EventLoop {

    private static final Logger log = LoggerFactory.getLogger(EventLoop.class);

    // KeyMap operation constants
    public static final String OP_MOUSE = "MOUSE";
    public static final String OP_ENTER = "ENTER";
    public static final String OP_UP = "UP";
    public static final String OP_DOWN = "DOWN";
    public static final String OP_LEFT = "LEFT";
    public static final String OP_RIGHT = "RIGHT";
    public static final String OP_BACKSPACE = "BACKSPACE";
    public static final String OP_DELETE = "DELETE";
    public static final String OP_TAB = "TAB";
    public static final String OP_CTRL_C = "CTRL_C";
    public static final String OP_CTRL_U = "CTRL_U";
    public static final String OP_CTRL_D = "CTRL_D";
    public static final String OP_CHAR = "CHAR";
    public static final String OP_ESC = "ESC";

    private final Terminal terminal;
    private final Screen screen;
    private final KeyHandler keyHandler;

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private volatile boolean running = true;
    private Runnable onResize;

    /**
     * 按鍵處理回呼介面：由 TuiAdapter 實作具體的業務邏輯。
     */
    public interface KeyHandler {
        /**
         * 處理按鍵事件。
         * @param operation KeyMap 匹配到的操作名（OP_ENTER, OP_CHAR 等）
         * @param lastBinding 最後匹配的原始字串（用於取得實際字元）
         */
        void handleKey(String operation, String lastBinding);

        /**
         * 處理滑鼠事件。
         */
        void handleMouse(MouseEvent event);
    }

    public EventLoop(Terminal terminal, Screen screen, KeyHandler keyHandler) {
        this.terminal = terminal;
        this.screen = screen;
        this.keyHandler = keyHandler;
    }

    /**
     * 啟動 TUI 事件迴圈（阻塞呼叫端直到退出）。
     *
     * 設計說明：
     * - 進入 alternate screen buffer（TUI 專用畫面，退出後恢復原終端機內容）
     * - 啟用 mouse tracking（Normal mode：按鍵 + 釋放 + 滾輪）
     * - 註冊 SIGWINCH 處理終端機大小變化
     * - input thread 阻塞讀取輸入，render thread 等 dirty 通知後重繪
     */
    public void run() {
        // 防禦性清理：無論前一次 Grimo 是正常退出還是被 SIGKILL 強制殺掉，
        // 都先關閉可能殘留的 mouse tracking 和 alternate screen。
        // 這是 TUI 應用的標準做法 — SIGKILL 無法被攔截，只能在下次啟動時清理。
        // 參考：https://jline.org/docs/advanced/terminal-attributes/
        cleanupStaleTerminalState();

        // 進入 TUI 模式（enterRawMode 回傳原始 Attributes，退出時用 setAttributes 恢復）
        Attributes savedAttributes = terminal.enterRawMode();
        terminal.puts(Capability.enter_ca_mode);    // alternate screen buffer
        terminal.puts(Capability.keypad_xmit);      // application keypad mode
        terminal.trackMouse(Terminal.MouseTracking.Button);
        terminal.flush();

        // SIGINT handler：委派給 keyHandler，走跟 BindingReader 相同的 OP_CTRL_C 路徑。
        // 設計說明：
        // - macOS JLine FFM 的 Signal.INT 在 JVM 層攔截 Ctrl+C，比 BindingReader 更早
        // - BindingReader 收不到字元 0x03，所以 OP_CTRL_C 不會從 inputLoop 觸發
        // - 直接委派給 keyHandler.handleKey() 讓 double-press 邏輯統一處理
        // - 在 MCP Manager / Slash Menu 模式下，Ctrl+C 會先關閉 overlay（跟 BindingReader 行為一致）
        terminal.handle(Terminal.Signal.INT, signal -> {
            keyHandler.handleKey(OP_CTRL_C, "\003");
            setDirty();
        });

        // JVM shutdown hook：備援（涵蓋 SIGTERM 等其他終止信號）
        Thread shutdownHook = new Thread(() -> restoreTerminal(savedAttributes), "grimo-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        // 終端機大小變化 → resize + 重繪
        terminal.handle(Terminal.Signal.WINCH, signal -> {
            if (onResize != null) onResize.run();
            screen.resize(terminal.getSize());
            screen.clear();
            setDirty();
        });

        // 初始渲染
        screen.resize(terminal.getSize());
        screen.clear();
        screen.render();

        // 雙執行緒：input thread + render thread (current thread)
        Thread inputThread = Thread.ofVirtual().name("grimo-input").start(this::inputLoop);

        try {
            renderLoop();
        } finally {
            running = false;
            inputThread.interrupt();
            restoreTerminal(savedAttributes);
            // 移除 shutdown hook（正常退出時不需要再執行）
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (IllegalStateException ignored) {}
        }
    }

    /**
     * 標記畫面需要重繪（thread-safe，任何執行緒都可呼叫）。
     *
     * 設計說明：
     * - Input thread 處理完按鍵後呼叫
     * - AI streaming token 到達時呼叫
     * - SIGWINCH 處理器呼叫
     * - 使用 AtomicBoolean + notify 喚醒 render thread
     */
    public void setDirty() {
        dirty.set(true);
        synchronized (dirty) {
            dirty.notifyAll();
        }
    }

    /**
     * 啟動前清理可能殘留的 terminal 狀態。
     * 當前一次 Grimo 被 SIGKILL 強制殺掉時，mouse tracking 和 alternate screen 可能殘留。
     * SIGKILL 無法被攔截（shutdown hook、signal handler 都無效），只能在下次啟動時清理。
     * 直接寫 ANSI escape sequence 而非用 JLine API，因為此時尚未進入 raw mode。
     */
    private void cleanupStaleTerminalState() {
        try {
            var writer = terminal.writer();
            writer.write("\033[?1000l");  // Disable X10 mouse tracking
            writer.write("\033[?1002l");  // Disable button-event mouse tracking
            writer.write("\033[?1003l");  // Disable all-event mouse tracking
            writer.write("\033[?1006l");  // Disable SGR extended mouse mode
            writer.write("\033[?1049l");  // Exit alternate screen buffer（如果殘留）
            writer.flush();
        } catch (Exception e) {
            log.debug("Stale terminal state cleanup failed: {}", e.getMessage());
        }
    }

    /**
     * 恢復 terminal 到正常狀態（cooked mode、關閉 mouse tracking、離開 alternate screen）。
     * 由 finally 和 shutdown hook 共用，確保任何退出路徑都能恢復。
     */
    private void restoreTerminal(Attributes savedAttributes) {
        try {
            terminal.trackMouse(Terminal.MouseTracking.Off);
            terminal.puts(Capability.keypad_local);
            terminal.puts(Capability.exit_ca_mode);
            terminal.flush();
            terminal.setAttributes(savedAttributes);
        } catch (Exception e) {
            // shutdown 時 terminal 可能已關閉，忽略錯誤
        }
    }

    /**
     * 停止事件迴圈。
     */
    public void stop() {
        running = false;
        setDirty(); // 喚醒 render thread 讓它退出
    }

    /**
     * 設定 resize 時的回呼（用於取消選取）
     */
    public void setOnResize(Runnable onResize) {
        this.onResize = onResize;
    }

    /**
     * Input thread：阻塞讀取鍵盤/滑鼠輸入。
     *
     * 設計說明：
     * - BindingReader.readBinding() 會阻塞直到有輸入
     * - Mouse event 先匹配 KeyMap 的 MOUSE binding，再用 readMouseEvent() 解析完整事件
     * - readMouseEvent(bindingReader::readCharacter, lastBinding) 確保 BindingReader
     *   已消耗的前綴被正確傳遞（JLine Tmux.java 同樣用法）
     */
    private void inputLoop() {
        BindingReader bindingReader = new BindingReader(terminal.reader());
        KeyMap<String> keyMap = buildKeyMap();

        try {
            while (running) {
                String op = bindingReader.readBinding(keyMap);
                if (op == null) break;

                if (OP_MOUSE.equals(op)) {
                    try {
                        MouseEvent mouse = terminal.readMouseEvent(
                                bindingReader::readCharacter,
                                bindingReader.getLastBinding());
                        if (mouse != null) {
                            keyHandler.handleMouse(mouse);
                        }
                    } catch (Exception e) {
                        log.debug("Mouse event read error: {}", e.getMessage());
                    }
                } else {
                    keyHandler.handleKey(op, bindingReader.getLastBinding());
                }
                setDirty();
            }
        } catch (Exception e) {
            if (running) {
                log.debug("Input loop ended: {}", e.getMessage());
            }
        }
    }

    /**
     * Render thread：等待 dirty 通知後重繪畫面。
     *
     * 設計說明：
     * - 使用 synchronized + wait 等待，被 setDirty() 的 notifyAll() 喚醒
     * - 100ms timeout 作為保底（避免 notify 遺失導致永久阻塞）
     * - Display.update() 內部做 diff，相同的行不會重繪
     */
    private void renderLoop() {
        while (running) {
            synchronized (dirty) {
                while (!dirty.compareAndSet(true, false) && running) {
                    try {
                        dirty.wait(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            if (running) {
                try {
                    screen.render();
                } catch (Exception e) {
                    log.debug("Render error: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 建構 KeyMap：綁定所有鍵盤和滑鼠操作。
     *
     * 設計說明：
     * - Mouse binding 綁兩個前綴解決 Spring Shell 的 bug：
     *   key_mouse capability（通常是 \033[M）+ SGR 前綴 \033[<
     * - 所有可印刷字元 (32-126) 綁到 OP_CHAR
     * - Ctrl 組合鍵直接綁 raw byte（Ctrl+C = 0x03, Ctrl+U = 0x15 等）
     */
    private KeyMap<String> buildKeyMap() {
        KeyMap<String> keyMap = new KeyMap<>();

        // Mouse（兩個前綴：X10 + SGR）
        String keyMouse = terminal.getStringCapability(Capability.key_mouse);
        if (keyMouse != null) {
            keyMap.bind(OP_MOUSE, keyMouse);
        }
        keyMap.bind(OP_MOUSE, "\033[<");  // SGR mouse prefix

        // Navigation keys
        keyMap.bind(OP_UP, KeyMap.key(terminal, Capability.key_up));
        keyMap.bind(OP_DOWN, KeyMap.key(terminal, Capability.key_down));
        keyMap.bind(OP_LEFT, KeyMap.key(terminal, Capability.key_left));
        keyMap.bind(OP_RIGHT, KeyMap.key(terminal, Capability.key_right));

        // Action keys
        keyMap.bind(OP_ENTER, "\r");
        keyMap.bind(OP_BACKSPACE, KeyMap.key(terminal, Capability.key_backspace));
        keyMap.bind(OP_BACKSPACE, "\177");  // DEL char（macOS Terminal.app）
        keyMap.bind(OP_DELETE, KeyMap.key(terminal, Capability.key_dc));
        keyMap.bind(OP_TAB, "\t");

        // Ctrl combinations (raw bytes)
        keyMap.bind(OP_CTRL_C, "\003");   // Ctrl+C = 0x03
        keyMap.bind(OP_CTRL_U, "\025");   // Ctrl+U = 0x15
        keyMap.bind(OP_CTRL_D, "\004");   // Ctrl+D = 0x04
        keyMap.bind(OP_ESC, "\033");     // ESC = 0x1B

        // Printable ASCII characters (32-126)
        for (char i = 32; i < 127; i++) {
            keyMap.bind(OP_CHAR, Character.toString(i));
        }

        // Unicode 字元（中文、日文等）：KeyMap 範圍外的字元用 setUnicode 處理
        keyMap.setUnicode(OP_CHAR);
        // 無匹配時也當作字元輸入（防止未綁定的序列被丟棄）
        keyMap.setNomatch(OP_CHAR);

        return keyMap;
    }
}
