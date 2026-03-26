package io.github.samzhu.grimo;

import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.MouseEvent;
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
public class GrimoEventLoop {

    private static final Logger log = LoggerFactory.getLogger(GrimoEventLoop.class);

    // KeyMap operation constants
    static final String OP_MOUSE = "MOUSE";
    static final String OP_ENTER = "ENTER";
    static final String OP_UP = "UP";
    static final String OP_DOWN = "DOWN";
    static final String OP_LEFT = "LEFT";
    static final String OP_RIGHT = "RIGHT";
    static final String OP_BACKSPACE = "BACKSPACE";
    static final String OP_DELETE = "DELETE";
    static final String OP_TAB = "TAB";
    static final String OP_CTRL_C = "CTRL_C";
    static final String OP_CTRL_U = "CTRL_U";
    static final String OP_CTRL_D = "CTRL_D";
    static final String OP_CHAR = "CHAR";

    private final Terminal terminal;
    private final GrimoScreen screen;
    private final KeyHandler keyHandler;

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private volatile boolean running = true;

    /**
     * 按鍵處理回呼介面：由 GrimoTuiRunner 實作具體的業務邏輯。
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

    public GrimoEventLoop(Terminal terminal, GrimoScreen screen, KeyHandler keyHandler) {
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
        // 進入 TUI 模式
        terminal.enterRawMode();
        terminal.puts(Capability.enter_ca_mode);    // alternate screen buffer
        terminal.puts(Capability.keypad_xmit);      // application keypad mode
        terminal.trackMouse(Terminal.MouseTracking.Normal);
        terminal.flush();

        // 終端機大小變化 → resize + 重繪
        terminal.handle(Terminal.Signal.WINCH, signal -> {
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
            // 恢復終端機狀態
            terminal.trackMouse(Terminal.MouseTracking.Off);
            terminal.puts(Capability.keypad_local);
            terminal.puts(Capability.exit_ca_mode);
            terminal.flush();
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
     * 停止事件迴圈。
     */
    public void stop() {
        running = false;
        setDirty(); // 喚醒 render thread 讓它退出
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

        // Printable characters (32-126)
        for (char i = 32; i < 127; i++) {
            keyMap.bind(OP_CHAR, Character.toString(i));
        }

        return keyMap;
    }
}
