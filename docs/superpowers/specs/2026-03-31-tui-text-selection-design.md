# TUI Text Selection Design

## Problem

Grimo TUI 無法選取和複製文字。根因是 JLine `trackMouse(MouseTracking.Normal)` 送出 `\033[?1005h` + `\033[?1006h` + `\033[?1000h`，啟用 SGR mouse mode 後終端把所有滑鼠事件（包括 Shift+click）都送給應用程式，不執行原生選取。Shift 只是 button code 的 bit 2，JLine 解析成 `MouseEvent.Modifier.Shift`，但 Grimo 的 `handleMouse()` 只處理 Wheel 事件，其餘靜默丟棄。

來源：JLine 3.30.6 `MouseSupport.trackMouse()` 原始碼確認。

## Prerequisites

**Mouse tracking 模式升級**：目前 `GrimoEventLoop` 使用 `MouseTracking.Normal`（mode 1000），只回報 press/release/wheel。文字選取需要 drag 事件，必須改用 `MouseTracking.Button`（mode 1002，`\033[?1002h`），回報 press/release/wheel + 按住按鍵時的 motion。

```java
// GrimoEventLoop.java — run() 中
terminal.trackMouse(Terminal.MouseTracking.Button);  // 原本是 Normal
```

JLine `MouseSupport.trackMouse()` 對 `Button` 送出 `\033[?1005h\033[?1006h\033[?1002h`。不影響既有的 Wheel 處理（mode 1002 是 mode 1000 的超集）。

## Solution

在 app 層實作文字選取 + OSC 52 剪貼簿複製。滑鼠拖曳反白選取，放開自動複製到系統剪貼簿。

## Design Decisions

| 決策 | 選擇 | 原因 |
|------|------|------|
| 座標系統 | Buffer-absolute | tmux/WezTerm 驗證模式：選取錨點不受 viewport 滾動影響 |
| 選取範圍 | 全螢幕（Content + Input + Status） | 偶爾也需要複製 input/status 的文字 |
| 複製觸發 | 滑鼠放開自動複製 | 最直覺，Claude Code 同樣做法 |
| Auto-scroll | Timer-based, 50ms, 1 line/tick | tmux 實作驗證，體感流暢 |
| 剪貼簿 | OSC 52 + native fallback | OSC 52 覆蓋 SSH 遠端場景，pbcopy/xclip 覆蓋 Terminal.app |
| 反白 Style | ANSI inverse (`\033[7m`) | 任何 color scheme 下都正確顯示 |
| 選取模式 | Stream only | 無 rectangular/block — YAGNI |

## Architecture

### Coordinate System

選取錨點用 buffer-absolute 座標，永遠不因 viewport 滾動而失效。

`GrimoContentView` 在 `render()` 時用 `line.columnSplitLength(cols)` 把原始行 wrap 成多行。目前只 wrap 可見範圍，需新增方法維護 **完整 wrapped line cache** 作為選取的參考座標。

**Wrapped line cache 策略**：
- `GrimoContentView.rebuildWrappedCache(int cols)` — 對所有 `lines` 做 `columnSplitLength(cols)`，快取結果
- 觸發時機：(1) terminal resize 時 cols 變化、(2) 新內容 append 時只增量 wrap 新行
- 既有的 `render()` 從 cache 取 viewport slice，不重複 wrap

```
Buffer index (flat, 0-based):
  0 .. wrappedContentSize-1  → Content 區域的 wrapped lines（完整歷史）
  wrappedContentSize         → Input 文字行（螢幕上的 3 行只有中間 1 行可選）
  wrappedContentSize + 1     → Status bar 行
```

螢幕上的 input 區域佔 3 行（separator + input + separator），但只有中間的 input 文字行映射到 buffer。兩條 separator 標記為不可選取。

螢幕 → buffer 轉換（滑鼠點擊時）：

```
Content 區域 (screenRow < contentHeight)：
    bufferRow = viewportStart + screenRow

Input separator (screenRow == contentHeight 或 contentHeight+2)：
    bufferRow = -1（不可選）

Input 文字行 (screenRow == contentHeight+1)：
    bufferRow = wrappedContentSize

Status bar (screenRow == lastRow)：
    bufferRow = wrappedContentSize + 1
```

buffer → 螢幕 轉換（渲染 highlight 時）：

```
Content：screenRow = bufferRow - viewportStart（不在 viewport 就不畫）
Input：固定螢幕位置 contentHeight+1
Status：固定螢幕位置 lastRow
```

每條 buffer line 帶 metadata：

```java
record BufferLine(AttributedString text, boolean wrapped, boolean selectable) {}
// wrapped = true → 這行是上一行的延續（copy 時不加 \n）
// selectable = false → separator 行，選取跳過
```

### Selection Model

模型只管座標和狀態，不碰渲染。參考 WezTerm 的乾淨分離。

```java
// shared/tui/TextSelection.java
class TextSelection {
    private int anchorRow, anchorCol;   // 按下時的 buffer 座標
    private int cursorRow, cursorCol;   // 拖曳時的 buffer 座標
    private boolean active;

    void startAt(int row, int col);
    void dragTo(int row, int col);
    String finish(List<BufferLine> buffer);  // 擷取文字、清除狀態
    void cancel();
    SelectionRange getRange();              // nullable
    boolean isActive();
}
```

正規化範圍（start ≤ end，無論拖曳方向）：

```java
record SelectionRange(int startRow, int startCol, int endRow, int endCol) {
    /** 某行在選取範圍內的列範圍。回傳 Optional.empty() 代表不在範圍內。 */
    Optional<ColSpan> colsForRow(int row, int lineWidth);

    record ColSpan(int start, int end) {}
    // 首行：startCol..lineWidth
    // 中間行：0..lineWidth
    // 末行：0..endCol
    // 單行：startCol..endCol
}
```

文字擷取邏輯：

```
for row in range.startRow..range.endRow (inclusive):
    if !buffer[row].selectable → skip
    用 columnSubSequence 擷取該行選取範圍的文字
    if row < range.endRow && row+1 < buffer.size() && buffer[row+1].wrapped:
        不加 \n（同一原始行的延續）
    else if row < range.endRow:
        加 \n
    // 最後一行不加 \n
```

參考 tmux `window_copy_copy_line()` 的 `GRID_LINE_WRAPPED` flag 做法。

CJK 處理：JLine 的 `AttributedString.columnSubSequence(start, end)` 已處理雙寬字元邊界，不需額外 padding cell 機制。

Thread safety：`TextSelection` 所有欄位用 `synchronized` 保護（物件本身當 lock），跟 `GrimoInputView` 同樣模式。`GrimoContentView` 的 wrapped cache 和 `scrollOffset` 也需加 `synchronized`，因為 AutoScroller thread 會呼叫 `scrollUp/Down()` 修改 `scrollOffset`，而 render thread 同時讀取。

**統一 buffer 的組裝者**：`GrimoScreen` 負責組裝 `List<BufferLine>`，因為它已經 compose 所有區域。在 `render()` 時從 `contentView.getBufferLines()` 取 content 部分，加上 input/status 的 `BufferLine`，組成完整 buffer 供 `TextSelection` 使用。`TextSelection`（在 `shared/tui/`）只接收 `List<BufferLine>` 參數，不依賴任何功能模組。

### Highlight Rendering

在 `GrimoScreen.render()` 組合完所有行後、送進 `Display.update()` 前，逐行疊加反白 style。

```java
List<AttributedString> applyHighlight(
    List<AttributedString> screenLines,
    int contentViewportStart,
    int contentHeight,
    int inputScreenRow,
    int statusScreenRow
) {
    SelectionRange range = getRange();
    if (range == null) return screenLines;

    List<AttributedString> result = new ArrayList<>(screenLines);
    for (int screenRow = 0; screenRow < screenLines.size(); screenRow++) {
        int bufferRow = screenToBuffer(screenRow, ...);
        Optional<ColSpan> span = range.colsForRow(bufferRow, screenLines.get(screenRow).columnLength());
        if (span.isEmpty()) continue;
        result.set(screenRow, applyInvertStyle(screenLines.get(screenRow), span.get().start(), span.get().end()));
    }
    return result;
}
```

反白用 `AttributedStyle.inverse()` → ANSI `\033[7m`，所有終端支援。

效能：`Display.update()` 內部做 diff，只有 highlight 改變的行才重繪。拖曳時只有邊緣行變化，中間行不變，diff 效率高。

### Auto-Scroll

拖曳到 content 區域邊緣時自動捲動。參考 tmux：timer-based，50ms，1 line/tick。

```java
// shared/tui/AutoScroller.java
class AutoScroller {
    private static final long INTERVAL_MS = 50;

    /** 建構時注入回呼，避免 AutoScroller 直接依賴 View 層 */
    AutoScroller(Runnable scrollUp, Runnable scrollDown,
                 IntConsumer onScrolled,   // 捲動後更新 selection cursor (delta: -1 or +1)
                 Runnable setDirty);       // 觸發重繪

    void update(int screenRow, int contentHeight);  // 邊緣 → 啟動，其他 → 停止
    void stop();
}
```

建構時由 `GrimoTuiRunner` 注入：
- `scrollUp` → `contentView::scrollUp`
- `scrollDown` → `contentView::scrollDown`
- `onScrolled` → `delta -> textSelection.dragTo(cursorRow + delta, cursorCol)`
- `setDirty` → `eventLoop::setDirty`

- Virtual thread timer，不佔 OS thread
- 每次 scroll 1 行 + 更新 `textSelection.dragTo(cursorRow ± 1, cursorCol)` + `setDirty()`
- 只在 content 區域觸發，input/status 不觸發
- mouse release 或拖離邊緣時停止
- Thread safety：AutoScroller 呼叫 `contentView.scrollUp/Down()` 修改 `scrollOffset`，render thread 同時讀取。`GrimoContentView` 的 `scrollOffset` 和 wrapped cache 需用 `synchronized` 保護（見 Selection Model 的 thread safety 說明）

### Clipboard Writer

OSC 52 優先 + 本機指令 fallback，兩個都送。

```java
// shared/tui/ClipboardWriter.java
class ClipboardWriter {
    void copy(Terminal terminal, String text) {
        writeOsc52(terminal, text);  // 對支援的終端立即生效，不支援的靜默忽略
        nativeCopy(text);            // pbcopy/xclip fallback，覆蓋 Terminal.app
    }
}
```

OSC 52 格式：`\033]52;c;<base64>\007`
- `\007` (BEL) 作為 ST — tmux/neovim 驗證，比 `\033\\` 相容性更好
- `Base64.getEncoder()`（不用 `getMimeEncoder`，後者會加換行）
- tmux 偵測：檢查 `System.getenv("TMUX") != null`，需 DCS passthrough 包裝：`\033Ptmux;\033<osc52>\033\\`
- GNU screen 偵測：檢查 `System.getenv("TERM")` 以 `screen` 開頭，需 DCS 包裝：`\033P<osc52>\033\\`
- 100KB 以上只走 native fallback

native fallback 在 virtual thread 上非同步執行 `pbcopy`/`xclip`，不阻塞渲染。

終端相容性：

| 終端 | OSC 52 | native fallback |
|------|:---:|:---:|
| iTerm2 | ✅ | ✅ |
| WezTerm | ✅ | ✅ |
| Ghostty | ✅ | ✅ |
| Alacritty | ✅ | ✅ |
| Terminal.app | ❌ | ✅ pbcopy |
| SSH 遠端 | ✅ | ❌ |

## Overlay Interaction

當 slash menu 或 MCP manager overlay 可見時，overlay 覆蓋 content 區域的底部行。選取在 overlay 顯示期間 **停用**：

- `handleMouse()` 在處理 Press/Drag/Release 前，先檢查 `screen.isSlashMenuVisible() || screen.isMcpManagerVisible()`
- 如果 overlay 可見 → 不啟動選取，直接 return（保留既有的 overlay 互動行為）
- 如果選取進行中 overlay 突然出現（理論上不會，因為 overlay 由鍵盤觸發，鍵盤會取消選取）→ 作為防禦，cancel 選取

這避免了 screenToBuffer 映射在 overlay 覆蓋時的歧義問題。

## Event Flow

### 滑鼠事件處理

```java
// GrimoTuiRunner — handleMouse() 擴充
// 前置檢查：overlay 可見時不處理選取
if (screen.isSlashMenuVisible() || screen.isMcpManagerVisible()) → 不處理選取，return

Wheel       → 捲動（不變）
Press+Btn1  → bufferRow = screenToBuffer(y)
              if bufferRow == -1 → 不可選行（separator），忽略
              textSelection.startAt(bufferRow, x)
Dragged     → bufferRow = screenToBuffer(y)
              // 拖曳到 content 區域外時，clamp 到 content 邊界
              // 這允許跨區選取（content → input → status），
              // 但 auto-scroll 只在 content 區域邊緣觸發
              if bufferRow == -1 → 忽略（separator 行）
              textSelection.dragTo(bufferRow, x)
              autoScroller.update(y, contentHeight)  // 只有 y==0 或 y==contentHeight-1 才觸發
Release+Btn1 → autoScroller.stop()
              → text = textSelection.finish(screenBuffer)
              → clipboardWriter.copy(terminal, text)
              → statusView.setTemporaryMessage("✓ Copied!", 2s)
```

### 鍵盤取消選取

```java
// handleKey() 開頭
if (textSelection.isActive()) {
    textSelection.cancel();
    autoScroller.stop();
}
```

任何鍵盤輸入取消選取，跟瀏覽器行為一致。

### Terminal Resize 取消選取

Resize 改變 `cols`，使 wrapped line cache 失效，所有 buffer-absolute 座標變成 stale。在 `GrimoEventLoop` 的 SIGWINCH handler 中，resize 時取消選取：

```java
terminal.handle(Terminal.Signal.WINCH, signal -> {
    textSelection.cancel();
    autoScroller.stop();
    screen.resize(terminal.getSize());
    screen.clear();
    setDirty();
});
```

`contentView.rebuildWrappedCache(newCols)` 在 resize 後由 render 觸發重建。

### 點擊（非拖曳）

press 後直接 release 無 drag → `finish()` 回傳空字串 → 不觸發複製 → 清除 highlight。

## File Changes

### New Files

| 檔案 | 位置 | 職責 |
|------|------|------|
| `TextSelection.java` | `shared/tui/` | 選取模型：anchor/cursor、範圍正規化、文字擷取 |
| `SelectionRange.java` | `shared/tui/` | 正規化範圍 record + `colsForRow()` |
| `BufferLine.java` | `shared/tui/` | metadata record |
| `AutoScroller.java` | `shared/tui/` | 邊緣自動捲動 |
| `ClipboardWriter.java` | `shared/tui/` | OSC 52 + native fallback |

### Modified Files

| 檔案 | 改動 |
|------|------|
| `GrimoEventLoop.java` | `MouseTracking.Normal` → `MouseTracking.Button`（啟用 drag 事件回報）+ SIGWINCH handler 加 selection cancel |
| `GrimoContentView.java` | 新增 wrapped line cache + `getBufferLines()` + `scrollOffset` synchronized |
| `GrimoScreen.java` | render 尾部插入 `textSelection.applyHighlight()`，組裝統一 `List<BufferLine>`，維護 screenToBuffer 映射 |
| `GrimoTuiRunner.java` | `handleMouse()` 擴充 Press/Drag/Release + overlay guard，持有 TextSelection/AutoScroller/ClipboardWriter，`handleKey()` 開頭加取消選取 |
| `GrimoStatusView.java` | 新增 `setTemporaryMessage()` |

### Unchanged
- `TuiComponent` 介面
- `DisplayWidth`、`Layout`、`TuiTable` 等既有 TUI 元件
- 各 View 不需知道選取的存在

## Testing

| 測試類 | 涵蓋 |
|------|------|
| `TextSelectionTest` | 單行/多行選取、反向拖曳正規化、CJK columnSubSequence 邊界、wrapped 行文字擷取不加多餘 \n、cancel/finish 狀態清除 |
| `SelectionRangeTest` | `colsForRow()` 首行/中間/末行/單行/不在範圍 |
| `AutoScrollerTest` | 邊緣觸發/停止、方向切換 |
| `ClipboardWriterTest` | OSC 52 序列格式（BEL terminator、Base64 無換行）、tmux DCS 包裝、大小限制 |

## References

- tmux `window-copy.c` — selection model, mouse drag, auto-scroll timer (50ms), `GRID_LINE_WRAPPED`
- tmux `screen.c` — `screen_set_selection()`, `screen_check_selection()`, `screen_select_cell()`
- tmux `tty.c:2090` — OSC 52 output via `tty_set_selection()`
- WezTerm `selection.rs` — `StableRowIndex` (buffer-absolute coordinates), `SelectionRange.cols_for_row()`
- WezTerm `termwindow/selection.rs` — mouse drag with 50% cell offset
- Textual `selection.py` — `Selection(NamedTuple)` with `get_span(y)`
- Textual `_text_area.py` — per-line highlight rendering in `_render_line()`
- JLine 3.30.6 `MouseSupport.java` — `trackMouse()` escape sequences, `parseMouseEvent()` modifier bit flags
- JLine 3.30.6 `Terminal.java` — `MouseTracking` enum, `MouseEvent.Modifier.Shift`
