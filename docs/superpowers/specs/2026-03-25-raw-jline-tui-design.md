# Raw JLine TUI 設計規格：取代 Spring Shell TerminalUI

> 日期：2026-03-25
> 狀態：設計完成

## 動機

Spring Shell 4.0.1 TerminalUI 的 mouse event parsing 有 bug：
- `trackMouse(Normal)` 啟用 SGR mode 1006，終端機發送 `\033[<Cb;Cx;CyM` 格式
- `KeyBinder` 只綁定 `\033[M`（X10 格式），SGR 前綴 `\033[<` 無 binding
- 導致 raw bytes 逐字元洩漏到 `keyEvents()` 成亂碼
- `mouseEvents()` 永遠不觸發
- Spring Shell 維護者開了 [#1085](https://github.com/spring-projects/spring-shell/issues/1085) 要求能關閉 mouse support，無修復計畫

## 方案

用 JLine 3 的 `Display` + `BindingReader` + `KeyMap` 取代 Spring Shell TerminalUI，保留 Spring Shell 命令基礎設施。

## 架構

```
GrimoTuiRunner (ApplicationRunner)
│
├─ 啟動流程（不變：workspace、agent、skill、MCP、task）
│
└─ GrimoEventLoop                          ← 新建
    │
    ├─ Thread 1: inputLoop()               ← BindingReader.readBinding() [阻塞]
    │   ├─ key event → KeyHandler → setDirty()
    │   └─ mouse event → readMouseEvent() → scrollUp/Down → setDirty()
    │
    ├─ Thread 2: renderLoop()              ← synchronized(dirty) { wait() }
    │   ├─ GrimoScreen.render() → List<AttributedString>
    │   └─ display.update(lines, cursorPos)
    │
    ├─ Async: AI streaming                 ← contentView.appendChunk() → setDirty()
    ├─ Signal: SIGWINCH                    ← display.resize() → setDirty()
    │
    └─ GrimoScreen                         ← 畫面組合器
        ├─ contentView.render(cols, rows)
        ├─ inputView.render(cols)
        ├─ statusView.render(cols)
        └─ slashMenuView.render(cols)      ← overlay（modal 模式時）
```

### 設計決策

**雙執行緒 + dirty flag（Tmux 模式）**：
- 參考 JLine 自己的 `Tmux.java` 實作（`inputLoop()` + `redrawLoop()` + `setDirty()`）
- Input thread 阻塞等輸入，render thread 等 dirty 通知後重繪
- AI streaming token 到達時也呼叫 `setDirty()` 喚醒 render thread
- 來源：[JLine Tmux.java](https://github.com/jline/jline3/blob/master/builtins/src/main/java/org/jline/builtins/Tmux.java)

**View 只產出 `List<AttributedString>`**：
- 所有 View 不接觸 Terminal，只負責產出行列表
- 渲染統一由 `GrimoScreen` → `Display.update()` 處理
- 好處：單一渲染路徑（不閃爍）、View 可單獨測試

**Mouse binding 綁兩個前綴**：
- `keyMap.bind(MOUSE, key(terminal, Capability.key_mouse))` → `\033[M`
- `keyMap.bind(MOUSE, "\033[<")` → SGR 格式
- 來源：JLine `MouseSupport.keys(terminal)` 回傳兩種前綴

**Display diff-based 渲染**：
- `Display.update()` 比對新舊行，只重繪有變化的字元
- 同一行沒變 → 完全不輸出（EQUAL diff operation）
- 來源：[JLine Display.java](https://github.com/jline/jline3/blob/master/terminal/src/main/java/org/jline/utils/Display.java)

### 需手動處理

| 項目 | 說明 |
|------|------|
| Alternate screen | `terminal.puts(Capability.enter_ca_mode)` / `exit_ca_mode` |
| SIGWINCH | `terminal.handle(Signal.WINCH, ...)` → `display.resize()` + `setDirty()` |
| 行寬截斷 | View 層自行截斷超寬行（Display 不自動 wrap） |
| 填滿高度 | 組合後的行數不足螢幕高度時，補空白行 |

## 新建檔案

### GrimoEventLoop.java

Event loop 核心，管理雙執行緒、KeyMap、BindingReader、mouse tracking。

```java
public class GrimoEventLoop {
    private final Terminal terminal;
    private final GrimoScreen screen;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private volatile boolean running = true;

    // KeyMap bindings
    private static final String KEY_CHAR = "CHAR";
    private static final String KEY_MOUSE = "MOUSE";
    private static final String KEY_ENTER = "ENTER";
    private static final String KEY_UP = "UP";
    private static final String KEY_DOWN = "DOWN";
    // ... 其他 key bindings

    public void run() {
        terminal.enterRawMode();
        terminal.puts(Capability.enter_ca_mode);
        terminal.puts(Capability.keypad_xmit);
        terminal.trackMouse(Terminal.MouseTracking.Normal);
        terminal.flush();

        terminal.handle(Signal.WINCH, signal -> {
            screen.resize(terminal.getSize());
            setDirty();
        });

        // 初始渲染
        screen.resize(terminal.getSize());
        screen.render();

        // 雙執行緒
        Thread inputThread = Thread.ofVirtual().name("grimo-input").start(this::inputLoop);

        try {
            renderLoop();
        } finally {
            running = false;
            inputThread.interrupt();
            terminal.trackMouse(Terminal.MouseTracking.Off);
            terminal.puts(Capability.keypad_local);
            terminal.puts(Capability.exit_ca_mode);
            terminal.flush();
        }
    }

    private void inputLoop() {
        BindingReader bindingReader = new BindingReader(terminal.reader());
        KeyMap<String> keyMap = buildKeyMap();

        while (running) {
            String op = bindingReader.readBinding(keyMap);
            if (op == null) break;

            if (KEY_MOUSE.equals(op)) {
                MouseEvent mouse = terminal.readMouseEvent(
                    bindingReader::readCharacter,
                    bindingReader.getLastBinding());
                handleMouse(mouse);
            } else {
                handleKey(op, bindingReader);
            }
            setDirty();
        }
    }

    private void renderLoop() {
        while (running) {
            synchronized (dirty) {
                while (!dirty.compareAndSet(true, false) && running) {
                    try { dirty.wait(100); } catch (InterruptedException e) { break; }
                }
            }
            if (running) {
                screen.render();
            }
        }
    }

    public void setDirty() {
        dirty.set(true);
        synchronized (dirty) { dirty.notifyAll(); }
    }
}
```

### GrimoScreen.java

畫面組合器：收集各 View 的 `List<AttributedString>`，拼接後呼叫 `Display.update()`。

```java
public class GrimoScreen {
    private final Display display;
    private final GrimoContentView contentView;
    private final GrimoInputView inputView;
    private final GrimoStatusView statusView;
    private final GrimoSlashMenuView slashMenuView;
    private int rows, cols;
    private boolean slashMenuVisible = false;

    public void resize(Size size) {
        rows = size.getRows();
        cols = size.getColumns();
        display.resize(rows, cols);
    }

    public void render() {
        int statusHeight = 1;
        int inputHeight = 3;  // separator + input + separator
        int menuHeight = slashMenuVisible ? slashMenuView.getVisibleCount() : 0;
        int contentHeight = rows - inputHeight - statusHeight;

        List<AttributedString> lines = new ArrayList<>(rows);

        // Content 區（flex）
        List<AttributedString> contentLines = contentView.render(cols, contentHeight);

        // Slash menu overlay（覆蓋 content 底部）
        if (slashMenuVisible && menuHeight > 0) {
            List<AttributedString> menuLines = slashMenuView.render(cols);
            int overlayStart = contentLines.size() - menuHeight;
            for (int i = 0; i < menuLines.size() && overlayStart + i >= 0; i++) {
                contentLines.set(overlayStart + i, menuLines.get(i));
            }
        }
        lines.addAll(contentLines);

        // Input 區（固定 3 行）
        lines.addAll(inputView.render(cols));

        // Status 區（固定 1 行）
        lines.addAll(statusView.render(cols));

        // 確保填滿螢幕高度
        while (lines.size() < rows) {
            lines.add(AttributedString.EMPTY);
        }
        // 截斷到螢幕高度
        if (lines.size() > rows) {
            lines = lines.subList(0, rows);
        }

        // cursor 位置（input 區的游標）
        int cursorRow = contentHeight + 1;  // separator 之後
        int cursorCol = inputView.getCursorCol();
        int cursorPos = cursorRow * cols + cursorCol;

        display.update(lines, cursorPos);
    }
}
```

## 改寫檔案

### GrimoContentView.java

移除 `extends BoxView`，新增 `render(cols, rows)` 方法回傳 `List<AttributedString>`。

**保留不動**：`lines`、`scrollOffset`、`autoFollow`、所有 append/scroll 方法、style 常數。

**移除**：`extends BoxView`、`setDrawFunction()`、`drawContent(Screen, Rectangle)`。

**新增**：
```java
public List<AttributedString> render(int cols, int viewHeight) {
    List<AttributedString> result = new ArrayList<>(viewHeight);
    int totalLines = lines.size();

    if (totalLines <= viewHeight) {
        // 底部對齊：上方填空白
        for (int i = 0; i < viewHeight - totalLines; i++) {
            result.add(AttributedString.EMPTY);
        }
        for (var line : lines) {
            result.add(truncate(line, cols));
        }
    } else {
        // 根據 scrollOffset 取可見範圍
        int endIndex = autoFollow ? totalLines : Math.min(totalLines, scrollOffset);
        if (endIndex < viewHeight) endIndex = viewHeight;
        int startIndex = Math.max(0, endIndex - viewHeight);
        for (int i = startIndex; i < startIndex + viewHeight && i < totalLines; i++) {
            result.add(truncate(lines.get(i), cols));
        }
    }

    // 確保行數正確
    while (result.size() < viewHeight) {
        result.add(AttributedString.EMPTY);
    }
    return result;
}

private AttributedString truncate(AttributedString line, int maxWidth) {
    return line.columnSubSequence(0, maxWidth);
}
```

### GrimoInputView.java

移除 `extends BoxView`，新增 `render(cols)` 方法。

**保留不動**：`buffer`、`cursorPos`、所有文字操作/游標/斜線偵測方法。

**新增**：
```java
public List<AttributedString> render(int cols) {
    List<AttributedString> result = new ArrayList<>(3);
    String separator = "─".repeat(cols);

    // 上方分隔線
    result.add(new AttributedString(separator,
        AttributedStyle.DEFAULT.foreground(245)));

    // ❯ 前綴 + 輸入文字
    var sb = new AttributedStringBuilder();
    sb.styled(BRAND_STYLE, PROMPT);
    sb.append(buffer.toString());
    result.add(sb.toAttributedString().columnSubSequence(0, cols));

    // 下方分隔線
    result.add(new AttributedString(separator,
        AttributedStyle.DEFAULT.foreground(245)));

    return result;
}

public int getCursorCol() {
    return PROMPT.length() + cursorPos;
}
```

### GrimoSlashCommandListView.java → GrimoSlashMenuView.java

移除 `extends BoxView`，新增 `render(cols)` 方法。

**保留不動**：`allItems`、`filteredItems`、`selectedIndex`、`MenuItem`、filter/move/select 方法。

**新增**：
```java
public List<AttributedString> render(int cols) {
    List<AttributedString> result = new ArrayList<>();
    int visibleCount = getVisibleCount();
    for (int i = 0; i < visibleCount; i++) {
        var item = filteredItems.get(i);
        String text = String.format("  /%-20s %s", item.name(), item.description());
        if (text.length() > cols) text = text.substring(0, cols);

        AttributedStyle style = (i == selectedIndex)
            ? AttributedStyle.DEFAULT.foreground(BRAND_COLOR)
            : AttributedStyle.DEFAULT;
        result.add(new AttributedString(text, style));
    }
    return result;
}
```

### GrimoTuiRunner.java

移除所有 Spring Shell TUI import，改用 GrimoEventLoop。

**保留不動**：啟動流程（Phase 1-3）、history、processInput、buildMenuItems、所有載入方法。

**移除**：TerminalUI、GridView、DialogView、StatusBarView、mouse disable hack。

**改寫**：Phase 4-6 改用 GrimoEventLoop + GrimoScreen。

## 不動的檔案

| 檔案 | 原因 |
|------|------|
| 所有 `@Command` 類別 | Spring Shell core，TUI 無關 |
| `BannerRenderer.java` | 純字串產生 |
| `SessionWriter.java` | JSONL 寫入，無 TUI 依賴 |
| `SlashStrippingCommandParser.java` | 命令解析 |
| `GrimoCommandCompleter.java` | 補全候選 |
| `GrimoStartupRunner.java` | Bean 定義 |

## 參考來源

- [JLine Display.java — diff-based 渲染](https://github.com/jline/jline3/blob/master/terminal/src/main/java/org/jline/utils/Display.java)
- [JLine Tmux.java — 雙執行緒 event loop 模式](https://github.com/jline/jline3/blob/master/builtins/src/main/java/org/jline/builtins/Tmux.java)
- [JLine BindingReader.java — KeyMap input 解析](https://github.com/jline/jline3/blob/master/reader/src/main/java/org/jline/keymap/BindingReader.java)
- [JLine MouseSupport.java — SGR mouse event 解析](https://github.com/jline/jline3/blob/master/terminal/src/main/java/org/jline/terminal/impl/MouseSupport.java)
- [JLine Mouse Support 文件](https://jline.org/docs/advanced/mouse-support/)
- [Spring Shell TerminalUI source — 驗證 KeyMap binding bug](https://github.com/spring-projects/spring-shell)
- [Spring Shell Issue #1085 — 維護者要求能關閉 mouse](https://github.com/spring-projects/spring-shell/issues/1085)
- [Spring Shell Issue #1086 — redraw 閃爍問題](https://github.com/spring-projects/spring-shell/issues/1086)
