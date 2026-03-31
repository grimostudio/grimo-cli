# Text Selection Integration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the core selection components (from `2026-03-31-text-selection-core.md`) into the existing TUI: content view wrapped cache, mouse event handling, highlight rendering, clipboard copy, status feedback.

**Architecture:** Modify 5 existing files (GrimoEventLoop, GrimoContentView, GrimoScreen, GrimoStatusView, GrimoTuiRunner). No new files. All changes follow the existing event-driven pattern: mouse events → TextSelection model → render loop picks up highlight via `getRange()`.

**Tech Stack:** Java 25, JLine 3.30.6, Spring Shell 4.0.x

**Spec:** `docs/superpowers/specs/2026-03-31-tui-text-selection-design.md`

**Prerequisite:** Complete `2026-03-31-text-selection-core.md` first (BufferLine, SelectionRange, TextSelection, ClipboardWriter, AutoScroller).

**Glossary:** `docs/glossary.md` — see "Content 區", "Input 區", "Status 區", "分隔線", "斜線指令選單"

---

### Task 1: GrimoContentView — wrapped line cache + synchronized

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoContentView.java`

- [ ] **Step 1: Add synchronized to scrollOffset and lines access**

把 `lines`、`scrollOffset`、`autoFollow` 的所有存取加上 `synchronized(this)`。
AutoScroller 會在 virtual thread 上呼叫 `scrollUp/Down()`，render thread 同時讀取。

修改所有現有方法（`appendUserInput`, `appendAiReply`, `appendCommandOutput`, `appendError`, `appendLine`, `removeLastLine`, `clear`, `scrollUp`, `scrollDown`, `render`）加上 `synchronized` 關鍵字。

```java
// 所有 public 方法加 synchronized，例如：
public synchronized void scrollUp(int count) { ... }
public synchronized void scrollDown(int count) { ... }
public synchronized List<AttributedString> render(int cols, int viewHeight) { ... }
```

- [ ] **Step 2: Add wrapped line cache + getBufferLines()**

在 `GrimoContentView` 新增：

```java
// --- Wrapped line cache（選取座標系統的基礎） ---

/** 完整 wrapped line cache（所有歷史行 wrap 後的結果） */
private List<BufferLine> wrappedCache = new ArrayList<>();
private int cachedCols = -1;

/**
 * 重建 wrapped line cache。
 *
 * 設計說明：
 * - 對所有 lines 做 columnSplitLength(cols)，建立 BufferLine list
 * - 觸發時機：cols 改變（resize）或新內容 append
 * - 已 wrap 的行標記 wrapped=true（同一原始行的延續），
 *   讓 TextSelection.extractText() 知道不要插入 \n
 *
 * @param cols 終端寬度
 */
private void rebuildWrappedCache(int cols) {
    wrappedCache = new ArrayList<>();
    for (var line : lines) {
        if (line.columnLength() <= cols) {
            wrappedCache.add(BufferLine.of(line));
        } else {
            var splits = line.columnSplitLength(cols);
            for (int j = 0; j < splits.size(); j++) {
                wrappedCache.add(j == 0
                        ? BufferLine.of(splits.get(j))
                        : BufferLine.wrapped(splits.get(j)));
            }
        }
    }
    cachedCols = cols;
}

/**
 * 取得完整 wrapped line cache（供 GrimoScreen 組裝統一 buffer）。
 * 如果 cache 尚未建立或 cols 已變，先 rebuild。
 *
 * @param cols 目前終端寬度
 * @return 完整的 BufferLine list
 */
public synchronized List<BufferLine> getBufferLines(int cols) {
    if (cachedCols != cols) {
        rebuildWrappedCache(cols);
    }
    return List.copyOf(wrappedCache);
}

/**
 * 取得 viewport 起始行在 wrapped cache 中的 index。
 * 用於 screenToBuffer 轉換。
 *
 * 設計說明：
 * - scrollOffset 是 unwrapped lines 的索引，需轉換為 wrapped cache 索引
 * - 方法：算出 render() 實際顯示哪些 unwrapped lines，
 *   再把第一個 unwrapped line 映射到 wrappedCache 的起始位置
 * - 這與 render() 的 startIndex/endIndex 邏輯完全對齊
 */
public synchronized int getViewportStart(int cols, int viewHeight) {
    if (cachedCols != cols) {
        rebuildWrappedCache(cols);
    }
    int totalLines = lines.size();
    if (totalLines == 0 || viewHeight <= 0) return 0;

    // 複製 render() 的 startIndex 計算邏輯（unwrapped 座標）
    int startIndex;
    if (totalLines <= viewHeight) {
        startIndex = 0;
    } else if (autoFollow) {
        startIndex = Math.max(0, totalLines - viewHeight);
    } else {
        int endIndex = Math.min(totalLines, scrollOffset);
        if (endIndex < viewHeight) endIndex = viewHeight;
        startIndex = Math.max(0, endIndex - viewHeight);
    }

    // 把 unwrapped startIndex 轉成 wrappedCache 索引
    // wrappedStartIndex[i] = wrappedCache 中第 i 個 unwrapped line 的第一行
    return unwrappedToWrappedIndex(startIndex);
}

/**
 * 把 unwrapped line 索引轉為 wrappedCache 中的行號。
 * 遍歷 wrappedCache，計算第 n 個非 wrapped 行的位置。
 */
private int unwrappedToWrappedIndex(int unwrappedIndex) {
    int unwrapped = 0;
    for (int i = 0; i < wrappedCache.size(); i++) {
        if (unwrapped == unwrappedIndex) return i;
        // 下一個非 wrapped 行開始新的 unwrapped line
        if (i + 1 < wrappedCache.size() && !wrappedCache.get(i + 1).wrapped()) {
            unwrapped++;
        } else if (i + 1 >= wrappedCache.size()) {
            unwrapped++;
        }
    }
    return wrappedCache.size(); // 超出範圍
}
```

- [ ] **Step 3: Update append methods to incrementally update cache**

在每個 append 方法（`appendUserInput`, `appendAiReply`, `appendCommandOutput`, `appendError`, `appendLine`）的結尾，增量更新 cache：

```java
// 在 appendXxx() 結尾、scrollToBottomIfAutoFollow() 之後加：
if (cachedCols > 0) {
    // 增量 wrap 新加入的行（最後 1-2 行）
    var newLine = lines.getLast();
    if (newLine.columnLength() <= cachedCols) {
        wrappedCache.add(BufferLine.of(newLine));
    } else {
        var splits = newLine.columnSplitLength(cachedCols);
        for (int j = 0; j < splits.size(); j++) {
            wrappedCache.add(j == 0
                    ? BufferLine.of(splits.get(j))
                    : BufferLine.wrapped(splits.get(j)));
        }
    }
}
```

注意：`appendUserInput` 和 `appendAiReply` 各加兩行（內容行 + 空行），需要為兩者都增量更新。`clear()` 也要清空 `wrappedCache` 和重設 `cachedCols = -1`。

- [ ] **Step 4: Verify build compiles**

```bash
./gradlew compileJava 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoContentView.java
git commit -m "feat(tui): add wrapped line cache and synchronized access to ContentView"
```

---

### Task 2: GrimoStatusView — temporary message

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStatusView.java`

- [ ] **Step 1: Add setTemporaryMessage()**

```java
// 新增欄位
private volatile String temporaryMessage;

/**
 * 顯示暫時訊息（如 "✓ Copied!"），duration 後自動恢復。
 *
 * @param message  暫時訊息
 * @param duration 顯示持續時間
 */
public void setTemporaryMessage(String message, java.time.Duration duration) {
    this.temporaryMessage = message;
    Thread.ofVirtual().name("grimo-temp-msg").start(() -> {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.temporaryMessage = null;
    });
}
```

- [ ] **Step 2: Update render() to show temporary message**

在 `render()` 方法開頭加：

```java
@Override
public List<AttributedString> render(int cols) {
    // 暫時訊息覆蓋正常 status（如 "✓ Copied!"）
    String tempMsg = this.temporaryMessage;
    if (tempMsg != null) {
        return List.of(TuiStatusBar.of(tempMsg,
                AttributedStyle.DEFAULT.foreground(2), cols));  // green
    }

    // ... 原有 render 邏輯 ...
```

- [ ] **Step 3: Verify build compiles**

```bash
./gradlew compileJava 2>&1 | tail -5
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoStatusView.java
git commit -m "feat(tui): add temporary message support to StatusView"
```

---

### Task 3: GrimoEventLoop — MouseTracking.Button + SIGWINCH selection cancel

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoEventLoop.java`

- [ ] **Step 1: Change MouseTracking.Normal to MouseTracking.Button**

在 `run()` 方法中：

```java
// 改前：
terminal.trackMouse(Terminal.MouseTracking.Normal);
// 改後（啟用 drag 事件回報，mode 1002 是 mode 1000 的超集）：
terminal.trackMouse(Terminal.MouseTracking.Button);
```

- [ ] **Step 2: Add selection cancel callback to SIGWINCH handler**

新增建構子參數或 setter，讓外部注入 resize 時的 cancel callback：

```java
// 新增欄位
private Runnable onResize;

/** 設定 resize 時的回呼（用於取消選取） */
public void setOnResize(Runnable onResize) {
    this.onResize = onResize;
}

// 在 run() 的 SIGWINCH handler 中，原有邏輯前加：
terminal.handle(Terminal.Signal.WINCH, signal -> {
    if (onResize != null) onResize.run();  // 新增：取消選取
    screen.resize(terminal.getSize());
    screen.clear();
    setDirty();
});
```

- [ ] **Step 3: Update cleanupStaleTerminalState() for mode 1002**

`cleanupStaleTerminalState()` 已包含 `\033[?1002l`（disable button-event tracking），不需改動。確認即可。

- [ ] **Step 4: Verify build compiles**

```bash
./gradlew compileJava 2>&1 | tail -5
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoEventLoop.java
git commit -m "feat(tui): upgrade to MouseTracking.Button for drag events"
```

---

### Task 4: GrimoScreen — buffer assembly + highlight rendering

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoScreen.java`

- [ ] **Step 1: Add TextSelection dependency**

```java
// 新增欄位
private final TextSelection textSelection;

// 建構子加參數
public GrimoScreen(Terminal terminal, GrimoContentView contentView,
                    GrimoInputView inputView, GrimoStatusView statusView,
                    GrimoSlashMenuView slashMenuView,
                    GrimoMcpManagerView mcpManagerView,
                    TextSelection textSelection) {
    // ... 原有 ...
    this.textSelection = textSelection;
}
```

- [ ] **Step 2: Add screenToBuffer helper + buffer assembly**

```java
// 在 GrimoScreen 新增 import
import io.github.samzhu.grimo.shared.tui.BufferLine;
import io.github.samzhu.grimo.shared.tui.TextSelection;
import io.github.samzhu.grimo.shared.tui.SelectionRange;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import java.util.Optional;

/** 組裝完整 buffer 和 screenToBuffer 映射 */
private int contentViewportStart;
private List<BufferLine> screenBuffer;

/**
 * 組裝統一 buffer（content + input + status）。
 * 供 TextSelection.finish() 使用。
 */
private void assembleScreenBuffer(int contentHeight) {
    var bufferLines = contentView.getBufferLines(cols);
    wrappedContentSize = bufferLines.size();
    contentViewportStart = contentView.getViewportStart(cols, contentHeight);
    // input 和 status 加到 buffer 末尾
    screenBuffer = new ArrayList<>(bufferLines);
    // input 文字行（input separator 不加入 buffer）
    var inputLines = inputView.render(cols);
    if (inputLines.size() >= 2) {
        // inputLines: [separator, input, separator] — 只取中間
        screenBuffer.add(BufferLine.of(inputLines.get(1)));
    }
    // status 行
    var statusLines = statusView.render(cols);
    if (!statusLines.isEmpty()) {
        screenBuffer.add(BufferLine.of(statusLines.getFirst()));
    }
}

/** content wrapped lines 數量（assembleScreenBuffer 時快取） */
private int wrappedContentSize;

/**
 * 螢幕行號 → buffer 行號。
 *
 * @return buffer row，不可選行回傳 -1
 */
int screenToBuffer(int screenRow, int contentHeight) {
    if (screenRow < contentHeight) {
        return contentViewportStart + screenRow;
    }
    int inputSepTop = contentHeight;
    int inputTextRow = contentHeight + 1;
    int inputSepBot = contentHeight + 2;
    int statusRow = rows - 1;
    if (screenRow == inputSepTop || screenRow == inputSepBot) {
        return -1; // separator 不可選
    }
    if (screenRow == inputTextRow) {
        return wrappedContentSize; // input 在 buffer 尾部
    }
    if (screenRow == statusRow) {
        return wrappedContentSize + 1;
    }
    return -1;
}

/** 取得目前的 screenBuffer（供 GrimoTuiRunner 在 mouse release 時用） */
public List<BufferLine> getScreenBuffer() {
    return screenBuffer;
}

public int getContentViewportStart() {
    return contentViewportStart;
}
```

- [ ] **Step 3: Add highlight rendering to render()**

在 `render()` 方法中，`display.update(allLines, cursorPos)` 前插入 highlight：

```java
    // --- 在 display.update() 前插入 ---

    // 組裝 buffer（供 selection text extraction 和 highlight 用）
    assembleScreenBuffer(contentHeight);

    // 選取反白渲染
    allLines = applySelectionHighlight(allLines, contentHeight);

    // --- 原有的 display.update() ---
```

新增 `applySelectionHighlight` 方法：

```java
/**
 * 對選取範圍的行加上 inverse style。
 *
 * 設計說明：
 * - 在 GrimoScreen.render() 組合完所有行後、送進 Display.update() 前執行
 * - 用 AttributedStyle.INVERSE 作為選取反白色（所有終端支援）
 * - Display.update() 的 diff 演算法只重繪有變化的行，效能佳
 */
private List<AttributedString> applySelectionHighlight(
        List<AttributedString> screenLines, int contentHeight) {
    SelectionRange range = textSelection.getRange();
    if (range == null) return screenLines;

    var result = new ArrayList<>(screenLines);
    for (int screenRow = 0; screenRow < screenLines.size(); screenRow++) {
        int bufferRow = screenToBuffer(screenRow, contentHeight);
        if (bufferRow < 0) continue;
        var line = screenLines.get(screenRow);
        var span = range.colsForRow(bufferRow, line.columnLength());
        if (span.isEmpty()) continue;
        result.set(screenRow, applyInvertStyle(line, span.get().start(), span.get().end()));
    }
    return result;
}

/**
 * 對一行的指定列範圍套用 inverse style。
 *
 * 設計說明：
 * - 使用 AttributedStyle.INVERSE 做反白，不保留原始色彩（同 tmux 預設行為）
 * - columnSubSequence 已正確處理 CJK 雙寬字元邊界
 *
 * JLine API 查證：
 * - AttributedString.columnSubSequence(int start, int stop) → AttributedString
 * - AttributedStyle.INVERSE = DEFAULT.inverse() → ANSI \033[7m
 * - AttributedStringBuilder.styled(style, CharSequence) → 用指定 style 包裝文字
 */
private AttributedString applyInvertStyle(AttributedString line, int startCol, int endCol) {
    var builder = new AttributedStringBuilder();
    builder.append(line.columnSubSequence(0, startCol));
    builder.styled(AttributedStyle.INVERSE,
            line.columnSubSequence(startCol, endCol).toString());
    builder.append(line.columnSubSequence(endCol, line.columnLength()));
    return builder.toAttributedString();
}
```

**注意**：GrimoScreen 建構子變更會導致 GrimoTuiRunner 編譯失敗。Task 4 和 Task 5 必須一起完成後才能通過編譯。先做完 Task 5 Step 1-2 再一起驗證編譯。

---

### Task 5: GrimoTuiRunner — handleMouse integration + wiring

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`

- [ ] **Step 1: Add TextSelection, AutoScroller, ClipboardWriter fields**

在 `GrimoTuiRunner` class 新增：

```java
import io.github.samzhu.grimo.shared.tui.TextSelection;
import io.github.samzhu.grimo.shared.tui.AutoScroller;
import io.github.samzhu.grimo.shared.tui.ClipboardWriter;

// 欄位（在 run() 中初始化，因為需要 eventLoop 和 contentView）
private TextSelection textSelection;
private AutoScroller autoScroller;
private ClipboardWriter clipboardWriter;
```

- [ ] **Step 2: Initialize in run() method**

在 `run()` 方法中建構 TUI 元件時初始化：

```java
// 在建構 GrimoScreen 前
textSelection = new TextSelection();
clipboardWriter = new ClipboardWriter();

// GrimoScreen 建構加 textSelection 參數
screen = new GrimoScreen(terminal, contentView, inputView, statusView,
        slashMenuView, mcpManagerView, textSelection);

// 在建構 eventLoop 後
autoScroller = new AutoScroller(
    () -> contentView.scrollUp(1),
    () -> contentView.scrollDown(1),
    delta -> textSelection.dragTo(
            textSelection.getCursorRow() + delta,
            textSelection.getCursorCol()),
    () -> eventLoop.setDirty()
);

// 設定 resize callback
eventLoop.setOnResize(() -> {
    textSelection.cancel();
    autoScroller.stop();
});
```

- [ ] **Step 3: Expand handleMouse()**

替換 `TuiKeyHandler.handleMouse()`：

```java
@Override
public void handleMouse(MouseEvent event) {
    // Overlay 可見時不處理選取（保留既有 overlay 互動）
    if (screen.isSlashMenuVisible() || screen.isMcpManagerVisible()) {
        // 只保留滾輪
        if (event.getType() == MouseEvent.Type.Wheel) {
            handleWheel(event);
        }
        return;
    }

    switch (event.getType()) {
        case Wheel -> handleWheel(event);
        case Pressed -> {
            if (event.getButton() == MouseEvent.Button.Button1) {
                int contentHeight = screen.getRows() - 4; // 3 input + 1 status
                int bufferRow = screen.screenToBuffer(event.getY(), contentHeight);
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
                    if (text != null && !text.isEmpty()) {
                        clipboardWriter.copy(terminal, text);
                        statusView.setTemporaryMessage(
                                "✓ Copied!", java.time.Duration.ofSeconds(2));
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
```

- [ ] **Step 4: Add selection cancel to handleKey()**

在 `TuiKeyHandler.handleKey()` 開頭加：

```java
@Override
public void handleKey(String operation, String lastBinding) {
    // 任何鍵盤輸入取消選取（跟瀏覽器行為一致）
    if (textSelection.isActive()) {
        textSelection.cancel();
        autoScroller.stop();
    }
    // ... 原有邏輯 ...
```

- [ ] **Step 5: Verify build compiles**

```bash
./gradlew compileJava 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Run existing tests to ensure no regression**

```bash
./gradlew test 2>&1 | tail -10
```

Expected: all tests PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "feat(tui): wire text selection into mouse event handling"
```

---

### Task 6: Manual Testing Checklist

**不寫自動化測試**（integration 層測試需要完整 TUI 環境）。手動驗證：

- [ ] **Step 1: Build and run**

```bash
./gradlew build && ./gradlew bootRun
```

- [ ] **Step 2: Basic selection test**

1. 在 TUI 中輸入幾句話，等 AI 回覆
2. 用滑鼠在 AI 回覆文字上按住拖曳 → 應看到反白 highlight
3. 放開滑鼠 → status bar 應短暫顯示 "✓ Copied!"
4. 在其他地方 Cmd+V → 應貼出選取的文字

- [ ] **Step 3: Cross-area selection test**

1. 從 content 區拖曳到 input 區 → 應能跨區選取
2. 從 content 區拖曳到 status bar → 同上
3. Separator 行不應被選取

- [ ] **Step 4: Auto-scroll test**

1. 累積足夠對話讓 content 可捲動
2. 從中間按住拖曳到頂部邊緣 → content 應自動向上捲動，選取範圍持續擴大
3. 拖到底部邊緣 → 同上向下
4. 放開 → 應複製完整選取範圍

- [ ] **Step 5: Cancel test**

1. 選取一段文字（不放開）→ 按鍵盤任意鍵 → highlight 應消失
2. 選取一段文字 → 調整視窗大小 → highlight 應消失

- [ ] **Step 6: Overlay test**

1. 輸入 `/` 觸發 slash menu
2. 在 overlay 上嘗試拖曳 → 不應啟動選取
3. 關閉 overlay → 拖曳應正常選取

- [ ] **Step 7: CJK test**

1. 輸入中文文字，等 AI 用中文回覆
2. 選取中文文字 → 應正確複製，不應截斷半個字

- [ ] **Step 8: Scrollwheel still works**

1. 確認滾輪上下捲動不受選取功能影響
2. 選取途中用滾輪 → 滾輪應只捲動不影響選取

- [ ] **Step 9: Take screenshot and commit**

如果一切正常：

```bash
git add -A
git commit -m "feat: TUI text selection with OSC 52 clipboard — complete"
```
