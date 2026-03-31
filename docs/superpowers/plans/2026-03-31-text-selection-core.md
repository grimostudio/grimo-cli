# Text Selection Core Components — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the core selection model, clipboard writer, and auto-scroller as standalone `shared/tui/` components — all testable without TUI integration.

**Architecture:** Pure Java components in `shared/tui/` package. `BufferLine` (metadata record) → `SelectionRange` (range math) → `TextSelection` (stateful model) → `ClipboardWriter` (OSC 52 + native) → `AutoScroller` (timer-based edge scroll). No Spring dependencies, no Terminal access except ClipboardWriter.

**Tech Stack:** Java 25, JLine 3.30.6 (`AttributedString.columnSubSequence/columnLength`), JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-03-31-tui-text-selection-design.md`

**Glossary:** `docs/glossary.md` — see "Content 區", "Input 區", "Status 區", "分隔線" for layout terminology

---

### Task 1: BufferLine record

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/tui/BufferLine.java`

- [ ] **Step 1: Create BufferLine record**

```java
package io.github.samzhu.grimo.shared.tui;

import org.jline.utils.AttributedString;

/**
 * 螢幕 buffer 中的一行，附帶選取相關 metadata。
 *
 * 設計說明：
 * - wrapped = true → 這行是上一行 columnSplitLength() 切分出的延續行，
 *   文字擷取時不加 \n（參考 tmux GRID_LINE_WRAPPED flag）
 * - selectable = false → separator 行等不可選取的裝飾行
 *
 * @see <a href="https://github.com/tmux/tmux/blob/master/grid.c">tmux grid.c — GRID_LINE_WRAPPED</a>
 */
public record BufferLine(AttributedString text, boolean wrapped, boolean selectable) {

    /** 建立可選取的非 wrap 行（最常見情境） */
    public static BufferLine of(AttributedString text) {
        return new BufferLine(text, false, true);
    }

    /** 建立可選取的 wrap 延續行 */
    public static BufferLine wrapped(AttributedString text) {
        return new BufferLine(text, true, true);
    }

    /** 建立不可選取的裝飾行（separator 等） */
    public static BufferLine unselectable(AttributedString text) {
        return new BufferLine(text, false, false);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/tui/BufferLine.java
git commit -m "feat(tui): add BufferLine record for selection metadata"
```

---

### Task 2: SelectionRange record + tests

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/tui/SelectionRange.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/tui/SelectionRangeTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package io.github.samzhu.grimo.shared.tui;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SelectionRangeTest {

    @Test
    void singleLineSelection() {
        // 選取第 3 行，col 5..10
        var range = new SelectionRange(3, 5, 3, 10);
        var span = range.colsForRow(3, 80);
        assertThat(span).isPresent();
        assertThat(span.get().start()).isEqualTo(5);
        assertThat(span.get().end()).isEqualTo(10);
    }

    @Test
    void multiLineFirstRow() {
        // 選取行 2..5，檢查首行
        var range = new SelectionRange(2, 10, 5, 20);
        var span = range.colsForRow(2, 80);
        assertThat(span).isPresent();
        assertThat(span.get().start()).isEqualTo(10);
        assertThat(span.get().end()).isEqualTo(80);  // 到行尾
    }

    @Test
    void multiLineMiddleRow() {
        var range = new SelectionRange(2, 10, 5, 20);
        var span = range.colsForRow(3, 80);
        assertThat(span).isPresent();
        assertThat(span.get().start()).isEqualTo(0);
        assertThat(span.get().end()).isEqualTo(80);  // 全行
    }

    @Test
    void multiLineLastRow() {
        var range = new SelectionRange(2, 10, 5, 20);
        var span = range.colsForRow(5, 80);
        assertThat(span).isPresent();
        assertThat(span.get().start()).isEqualTo(0);
        assertThat(span.get().end()).isEqualTo(20);
    }

    @Test
    void rowOutsideRange() {
        var range = new SelectionRange(2, 10, 5, 20);
        assertThat(range.colsForRow(0, 80)).isEmpty();
        assertThat(range.colsForRow(6, 80)).isEmpty();
    }

    @Test
    void zeroWidthLineReturnsEmpty() {
        var range = new SelectionRange(2, 10, 5, 20);
        // 行寬 0 → 無內容可選
        assertThat(range.colsForRow(3, 0)).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "io.github.samzhu.grimo.shared.tui.SelectionRangeTest" 2>&1 | tail -5
```

Expected: FAIL — `SelectionRange` class not found

- [ ] **Step 3: Implement SelectionRange**

```java
package io.github.samzhu.grimo.shared.tui;

import java.util.Optional;

/**
 * 正規化的選取範圍（start ≤ end）。
 *
 * 設計說明：
 * - 無論使用者從上往下或從下往上拖曳，startRow ≤ endRow 恆成立
 * - colsForRow() 回傳某一行在選取範圍內的列範圍，用於逐行渲染 highlight
 * - 參考 WezTerm SelectionRange.cols_for_row() 設計
 *
 * @param startRow 選取起始行（buffer-absolute）
 * @param startCol 起始列（column-based，CJK 字元佔 2 列）
 * @param endRow   選取結束行（buffer-absolute）
 * @param endCol   結束列
 *
 * @see <a href="https://github.com/wez/wezterm/blob/main/wezterm-gui/src/selection.rs">WezTerm selection.rs</a>
 */
public record SelectionRange(int startRow, int startCol, int endRow, int endCol) {

    /**
     * 某行在選取範圍內的列範圍。
     *
     * @param row       要查詢的行號（buffer-absolute）
     * @param lineWidth 該行的 column 寬度（用於「到行尾」的計算）
     * @return 列範圍，empty 代表該行不在選取範圍內
     */
    public Optional<ColSpan> colsForRow(int row, int lineWidth) {
        if (row < startRow || row > endRow || lineWidth <= 0) {
            return Optional.empty();
        }
        int s, e;
        if (startRow == endRow) {
            // 單行選取
            s = startCol;
            e = endCol;
        } else if (row == startRow) {
            // 首行：startCol → 行尾
            s = startCol;
            e = lineWidth;
        } else if (row == endRow) {
            // 末行：行首 → endCol
            s = 0;
            e = endCol;
        } else {
            // 中間行：全行
            s = 0;
            e = lineWidth;
        }
        // clamp
        s = Math.max(0, Math.min(s, lineWidth));
        e = Math.max(s, Math.min(e, lineWidth));
        if (s == e) return Optional.empty();
        return Optional.of(new ColSpan(s, e));
    }

    /** 列範圍（start inclusive, end exclusive） */
    public record ColSpan(int start, int end) {}
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "io.github.samzhu.grimo.shared.tui.SelectionRangeTest" 2>&1 | tail -5
```

Expected: all 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/tui/SelectionRange.java \
        src/test/java/io/github/samzhu/grimo/shared/tui/SelectionRangeTest.java
git commit -m "feat(tui): add SelectionRange with colsForRow() for per-line highlight"
```

---

### Task 3: TextSelection model + tests

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/tui/TextSelection.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/tui/TextSelectionTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package io.github.samzhu.grimo.shared.tui;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class TextSelectionTest {

    // 測試用 helper：建立簡單 buffer
    private List<BufferLine> buffer(String... texts) {
        return java.util.Arrays.stream(texts)
                .map(t -> BufferLine.of(new AttributedString(t)))
                .toList();
    }

    @Test
    void startAndFinishSingleLine() {
        var sel = new TextSelection();
        sel.startAt(0, 3);
        sel.dragTo(0, 8);
        var text = sel.finish(buffer("Hello, World!"));
        assertThat(text).isEqualTo("lo, W");
    }

    @Test
    void multiLineSelection() {
        var sel = new TextSelection();
        sel.startAt(0, 6);
        sel.dragTo(1, 5);
        var text = sel.finish(buffer("Hello, World!", "Goodbye Moon!"));
        assertThat(text).isEqualTo("World!\nGoodb");
    }

    @Test
    void reverseDragNormalizesRange() {
        // 從下往上拖曳
        var sel = new TextSelection();
        sel.startAt(1, 5);
        sel.dragTo(0, 3);
        var text = sel.finish(buffer("Hello, World!", "Goodbye Moon!"));
        assertThat(text).isEqualTo("lo, World!\nGoodb");
    }

    @Test
    void wrappedLinesDoNotInsertNewline() {
        // 模擬一行被 wrap 成兩行
        var lines = List.of(
                BufferLine.of(new AttributedString("Hello ")),
                BufferLine.wrapped(new AttributedString("World!")),
                BufferLine.of(new AttributedString("Second line"))
        );
        var sel = new TextSelection();
        sel.startAt(0, 0);
        sel.dragTo(2, 6);
        var text = sel.finish(lines);
        // "Hello " + "World!" 之間不加 \n（wrapped）
        // "World!" + "Second" 之間加 \n（非 wrapped）
        assertThat(text).isEqualTo("Hello World!\nSecond");
    }

    @Test
    void unselectableLinesAreSkipped() {
        var lines = List.of(
                BufferLine.of(new AttributedString("Line 1")),
                BufferLine.unselectable(new AttributedString("──────")),
                BufferLine.of(new AttributedString("Line 3"))
        );
        var sel = new TextSelection();
        sel.startAt(0, 0);
        sel.dragTo(2, 6);
        var text = sel.finish(lines);
        assertThat(text).isEqualTo("Line 1\nLine 3");
    }

    @Test
    void finishClearsState() {
        var sel = new TextSelection();
        sel.startAt(0, 0);
        sel.dragTo(0, 5);
        sel.finish(buffer("Hello"));
        assertThat(sel.isActive()).isFalse();
        assertThat(sel.getRange()).isNull();
    }

    @Test
    void cancelClearsState() {
        var sel = new TextSelection();
        sel.startAt(0, 0);
        sel.dragTo(0, 5);
        sel.cancel();
        assertThat(sel.isActive()).isFalse();
        assertThat(sel.getRange()).isNull();
    }

    @Test
    void clickWithoutDragReturnsEmpty() {
        var sel = new TextSelection();
        sel.startAt(3, 10);
        // 沒有 dragTo — anchor == cursor
        var text = sel.finish(buffer("Line0", "Line1", "Line2", "Line3 text here"));
        assertThat(text).isEmpty();
    }

    @Test
    void cjkColumnSubSequence() {
        // "你好World" — 你=col0-1, 好=col2-3, W=col4, o=col5, r=col6, l=col7, d=col8
        var sel = new TextSelection();
        sel.startAt(0, 2);
        sel.dragTo(0, 6);
        var text = sel.finish(List.of(
                BufferLine.of(new AttributedString("你好World"))));
        // columnSubSequence(2, 6) = "好Wor"
        assertThat(text).isEqualTo("好Wor");
    }

    @Test
    void getRangeReturnsNullWhenInactive() {
        var sel = new TextSelection();
        assertThat(sel.getRange()).isNull();
    }

    @Test
    void getRangeReturnsSameForBothDragDirections() {
        var sel = new TextSelection();
        sel.startAt(5, 10);
        sel.dragTo(2, 3);
        var range = sel.getRange();
        assertThat(range).isNotNull();
        assertThat(range.startRow()).isEqualTo(2);
        assertThat(range.startCol()).isEqualTo(3);
        assertThat(range.endRow()).isEqualTo(5);
        assertThat(range.endCol()).isEqualTo(10);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "io.github.samzhu.grimo.shared.tui.TextSelectionTest" 2>&1 | tail -5
```

Expected: FAIL — `TextSelection` class not found

- [ ] **Step 3: Implement TextSelection**

```java
package io.github.samzhu.grimo.shared.tui;

import org.jline.utils.AttributedString;
import java.util.List;

/**
 * 文字選取模型：管理 anchor/cursor 座標與文字擷取。
 *
 * 設計說明：
 * - 座標系統用 buffer-absolute（不受 viewport 滾動影響）
 *   參考 tmux sely/endsely 和 WezTerm StableRowIndex
 * - 模型只管座標和狀態，不碰渲染（渲染由 applyHighlight 在 Screen 層處理）
 * - Thread safety：所有欄位用 synchronized(this) 保護，
 *   因為 input thread（mouse events）和 render thread 同時存取
 *
 * @see <a href="https://github.com/tmux/tmux/blob/master/window-copy.c">tmux window-copy.c — selection model</a>
 * @see <a href="https://github.com/wez/wezterm/blob/main/wezterm-gui/src/selection.rs">WezTerm selection.rs</a>
 */
public class TextSelection {

    private int anchorRow, anchorCol;
    private int cursorRow, cursorCol;
    private boolean active;

    /** 開始選取（mouse press 時呼叫） */
    public synchronized void startAt(int row, int col) {
        this.anchorRow = row;
        this.anchorCol = col;
        this.cursorRow = row;
        this.cursorCol = col;
        this.active = true;
    }

    /** 更新游標位置（mouse drag 或 auto-scroll 時呼叫） */
    public synchronized void dragTo(int row, int col) {
        if (!active) return;
        this.cursorRow = row;
        this.cursorCol = col;
    }

    /**
     * 結束選取：擷取文字並清除狀態。
     *
     * 設計說明：
     * - wrapped 行之間不加 \n（參考 tmux GRID_LINE_WRAPPED）
     * - 不可選行（separator）直接跳過
     * - 最後一行不加尾部 \n
     * - columnSubSequence 已處理 CJK 雙寬字元邊界
     *
     * @param buffer 完整的螢幕 buffer（由 GrimoScreen 組裝）
     * @return 選取的純文字，anchor == cursor 時回傳空字串
     */
    public synchronized String finish(List<BufferLine> buffer) {
        if (!active) return "";
        var range = computeRange();
        active = false;
        if (range == null) return "";
        return extractText(buffer, range);
    }

    /** 取消選取 */
    public synchronized void cancel() {
        active = false;
    }

    /** 取得正規化範圍（用於渲染 highlight）。未選取時回傳 null。 */
    public synchronized SelectionRange getRange() {
        if (!active) return null;
        return computeRange();
    }

    public synchronized boolean isActive() {
        return active;
    }

    /** 取得目前 cursor 的 row（AutoScroller 用來計算下次 dragTo） */
    public synchronized int getCursorRow() {
        return cursorRow;
    }

    /** 取得目前 cursor 的 col */
    public synchronized int getCursorCol() {
        return cursorCol;
    }

    /**
     * 正規化：確保 start ≤ end（無論拖曳方向）。
     * anchor == cursor 時回傳 null（點擊無拖曳）。
     */
    private SelectionRange computeRange() {
        if (anchorRow == cursorRow && anchorCol == cursorCol) return null;
        int sr, sc, er, ec;
        if (anchorRow < cursorRow || (anchorRow == cursorRow && anchorCol < cursorCol)) {
            sr = anchorRow; sc = anchorCol; er = cursorRow; ec = cursorCol;
        } else {
            sr = cursorRow; sc = cursorCol; er = anchorRow; ec = anchorCol;
        }
        return new SelectionRange(sr, sc, er, ec);
    }

    private String extractText(List<BufferLine> buffer, SelectionRange range) {
        var sb = new StringBuilder();
        for (int row = range.startRow(); row <= range.endRow() && row < buffer.size(); row++) {
            var line = buffer.get(row);
            if (!line.selectable()) continue;

            var span = range.colsForRow(row, line.text().columnLength());
            if (span.isEmpty()) continue;

            var sub = line.text().columnSubSequence(span.get().start(), span.get().end());
            sb.append(sub.toString());

            // wrapped 行之間不加 \n，最後一行也不加
            if (row < range.endRow()) {
                boolean nextWrapped = (row + 1 < buffer.size()) && buffer.get(row + 1).wrapped();
                if (!nextWrapped) {
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "io.github.samzhu.grimo.shared.tui.TextSelectionTest" 2>&1 | tail -5
```

Expected: all 11 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/tui/TextSelection.java \
        src/test/java/io/github/samzhu/grimo/shared/tui/TextSelectionTest.java
git commit -m "feat(tui): add TextSelection model with buffer-absolute coordinates"
```

---

### Task 4: ClipboardWriter + tests

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/tui/ClipboardWriter.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/tui/ClipboardWriterTest.java`

- [ ] **Step 1: Write the failing tests**

注意：`writeOsc52` 的輸出測試用 `StringWriter` 模擬 terminal writer。`nativeCopy` 走 subprocess 不在 unit test 測試範圍。

```java
package io.github.samzhu.grimo.shared.tui;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class ClipboardWriterTest {

    @Test
    void osc52FormatUsesBase64AndBel() {
        String text = "Hello World";
        String expected = Base64.getEncoder().encodeToString(text.getBytes(UTF_8));
        String seq = ClipboardWriter.buildOsc52Sequence(text, false, false);
        assertThat(seq).isEqualTo("\033]52;c;" + expected + "\007");
    }

    @Test
    void osc52Base64HasNoLineBreaks() {
        // 長字串確認 Base64 不含換行
        String text = "a".repeat(200);
        String seq = ClipboardWriter.buildOsc52Sequence(text, false, false);
        assertThat(seq).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void osc52TmuxWrapsDcs() {
        String text = "test";
        String seq = ClipboardWriter.buildOsc52Sequence(text, true, false);
        // DCS passthrough: ESC P tmux; ESC <osc52> ESC backslash
        assertThat(seq).startsWith("\033Ptmux;\033");
        assertThat(seq).endsWith("\033\\");
        // 內部仍包含 OSC 52 序列
        String b64 = Base64.getEncoder().encodeToString(text.getBytes(UTF_8));
        assertThat(seq).contains("\033]52;c;" + b64 + "\007");
    }

    @Test
    void osc52ScreenWrapsDcs() {
        String text = "test";
        String seq = ClipboardWriter.buildOsc52Sequence(text, false, true);
        assertThat(seq).startsWith("\033P");
        assertThat(seq).endsWith("\033\\");
    }

    @Test
    void isWithinOsc52LimitAccepts99KB() {
        String small = "x".repeat(99_000);
        assertThat(ClipboardWriter.isWithinOsc52Limit(small)).isTrue();
    }

    @Test
    void isWithinOsc52LimitRejects101KB() {
        String large = "x".repeat(101_000);
        assertThat(ClipboardWriter.isWithinOsc52Limit(large)).isFalse();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "io.github.samzhu.grimo.shared.tui.ClipboardWriterTest" 2>&1 | tail -5
```

Expected: FAIL

- [ ] **Step 3: Implement ClipboardWriter**

```java
package io.github.samzhu.grimo.shared.tui;

import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 系統剪貼簿寫入：OSC 52 + native fallback（pbcopy/xclip）。
 *
 * 設計說明：
 * - 兩個管道都送：OSC 52 覆蓋 SSH 遠端場景，native 覆蓋 Terminal.app（不支援 OSC 52）
 * - OSC 52 用 BEL (\007) 作為 ST，比 ESC \ 相容性更好（tmux/neovim 驗證）
 * - Base64 用 java.util.Base64.getEncoder()（不用 getMimeEncoder，後者會加換行）
 * - 100KB 以上只走 native fallback（避免 OSC 52 被終端截斷）
 * - native fallback 在 virtual thread 上非同步執行，不阻塞渲染
 *
 * @see <a href="https://github.com/tmux/tmux/blob/master/tty.c">tmux tty.c:2090 — tty_set_selection()</a>
 */
public class ClipboardWriter {

    private static final Logger log = LoggerFactory.getLogger(ClipboardWriter.class);
    private static final int OSC52_MAX_BYTES = 100_000;

    /**
     * 複製文字到系統剪貼簿。
     *
     * @param terminal JLine Terminal（用於寫出 OSC 52）
     * @param text     要複製的文字
     */
    public void copy(Terminal terminal, String text) {
        if (text == null || text.isEmpty()) return;

        // OSC 52（100KB 以下才送，超過可能被終端截斷）
        if (isWithinOsc52Limit(text)) {
            String seq = buildOsc52Sequence(text, isTmux(), isScreen());
            terminal.writer().write(seq);
            terminal.writer().flush();
        }

        // native fallback（非同步，不阻塞）
        Thread.ofVirtual().name("grimo-clipboard").start(() -> nativeCopy(text));
    }

    /**
     * 建構 OSC 52 escape sequence（package-private，供測試用）。
     *
     * @param text     原始文字
     * @param tmux     是否在 tmux 內（需 DCS passthrough）
     * @param screen   是否在 GNU screen 內（需 DCS 包裝）
     * @return 完整的 escape sequence
     */
    static String buildOsc52Sequence(String text, boolean tmux, boolean screen) {
        String base64 = Base64.getEncoder().encodeToString(text.getBytes(UTF_8));
        String osc52 = "\033]52;c;" + base64 + "\007";

        if (tmux) {
            return "\033Ptmux;\033" + osc52 + "\033\\";
        } else if (screen) {
            return "\033P" + osc52 + "\033\\";
        }
        return osc52;
    }

    static boolean isWithinOsc52Limit(String text) {
        return text.getBytes(UTF_8).length <= OSC52_MAX_BYTES;
    }

    private static boolean isTmux() {
        return System.getenv("TMUX") != null;
    }

    private static boolean isScreen() {
        String term = System.getenv("TERM");
        return term != null && term.startsWith("screen");
    }

    private void nativeCopy(String text) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            String[][] cmds;
            if (os.contains("mac")) {
                cmds = new String[][]{{"pbcopy"}};
            } else {
                // Linux: try xclip first, fall back to xsel
                cmds = new String[][]{
                    {"xclip", "-selection", "clipboard"},
                    {"xsel", "--clipboard", "--input"}
                };
            }
            for (String[] cmd : cmds) {
                try {
                    Process p = new ProcessBuilder(cmd)
                            .redirectErrorStream(true)
                            .start();
                    p.getOutputStream().write(text.getBytes(UTF_8));
                    p.getOutputStream().close();
                    if (p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0) {
                        return; // 成功，不嘗試下一個
                    }
                } catch (Exception ignored) {
                    // 此命令不可用，嘗試下一個
                }
            }
        } catch (Exception e) {
            log.debug("Native clipboard copy failed: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "io.github.samzhu.grimo.shared.tui.ClipboardWriterTest" 2>&1 | tail -5
```

Expected: all 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/tui/ClipboardWriter.java \
        src/test/java/io/github/samzhu/grimo/shared/tui/ClipboardWriterTest.java
git commit -m "feat(tui): add ClipboardWriter with OSC 52 + pbcopy fallback"
```

---

### Task 5: AutoScroller + tests

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/tui/AutoScroller.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/tui/AutoScrollerTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package io.github.samzhu.grimo.shared.tui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class AutoScrollerTest {

    private AutoScroller scroller;

    @AfterEach
    void cleanup() {
        if (scroller != null) scroller.stop();
    }

    @Test
    void topEdgeTriggersScrollUp() throws InterruptedException {
        var upCount = new AtomicInteger();
        var downCount = new AtomicInteger();
        scroller = new AutoScroller(
                () -> upCount.incrementAndGet(),
                () -> downCount.incrementAndGet(),
                delta -> {},
                () -> {}
        );
        scroller.update(0, 20);  // screenRow == 0 → 上邊緣
        Thread.sleep(200);       // 寬裕等待（50ms * 3+ ticks），降低 flaky 風險
        scroller.stop();
        assertThat(upCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(downCount.get()).isZero();
    }

    @Test
    void bottomEdgeTriggersScrollDown() throws InterruptedException {
        var downCount = new AtomicInteger();
        scroller = new AutoScroller(
                () -> {}, () -> downCount.incrementAndGet(), delta -> {}, () -> {}
        );
        scroller.update(19, 20);  // screenRow == contentHeight-1 → 下邊緣
        Thread.sleep(200);
        scroller.stop();
        assertThat(downCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void middlePositionDoesNotScroll() throws InterruptedException {
        var count = new AtomicInteger();
        scroller = new AutoScroller(
                () -> count.incrementAndGet(),
                () -> count.incrementAndGet(),
                delta -> {}, () -> {}
        );
        scroller.update(10, 20);  // 中間 → 不捲動
        Thread.sleep(150);
        scroller.stop();
        assertThat(count.get()).isZero();
    }

    @Test
    void stopCancelsScrolling() throws InterruptedException {
        var count = new AtomicInteger();
        scroller = new AutoScroller(
                () -> count.incrementAndGet(), () -> {}, delta -> {}, () -> {}
        );
        scroller.update(0, 20);
        Thread.sleep(100);
        scroller.stop();
        int afterStop = count.get();
        Thread.sleep(200);
        // stop 後計數不再增加（容忍 +1 因為 interrupt 可能在 run 和 sleep 之間）
        assertThat(count.get()).isLessThanOrEqualTo(afterStop + 1);
    }

    @Test
    void directionSwitchWorks() throws InterruptedException {
        var upCount = new AtomicInteger();
        var downCount = new AtomicInteger();
        scroller = new AutoScroller(
                () -> upCount.incrementAndGet(),
                () -> downCount.incrementAndGet(),
                delta -> {}, () -> {}
        );
        scroller.update(0, 20);   // 向上
        Thread.sleep(150);
        scroller.update(19, 20);  // 切換向下
        Thread.sleep(150);
        scroller.stop();
        assertThat(upCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(downCount.get()).isGreaterThanOrEqualTo(1);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "io.github.samzhu.grimo.shared.tui.AutoScrollerTest" 2>&1 | tail -5
```

Expected: FAIL

- [ ] **Step 3: Implement AutoScroller**

```java
package io.github.samzhu.grimo.shared.tui;

import java.util.function.IntConsumer;

/**
 * 拖曳邊緣自動捲動。
 *
 * 設計說明：
 * - 參考 tmux WINDOW_COPY_DRAG_REPEAT_TIME = 50ms
 * - 拖曳到 content 區域頂部（screenRow == 0）或底部（screenRow == contentHeight-1）時啟動
 * - Virtual thread timer，每 50ms 捲動 1 行
 * - 透過回呼注入 scrollUp/scrollDown/onScrolled/setDirty，不依賴 View 層
 *
 * @see <a href="https://github.com/tmux/tmux/blob/master/window-copy.c#L316">tmux drag timer</a>
 */
public class AutoScroller {

    private static final long INTERVAL_MS = 50;

    private final Runnable scrollUp;
    private final Runnable scrollDown;
    private final IntConsumer onScrolled;  // 捲動後更新 selection cursor（delta: -1 or +1）
    private final Runnable setDirty;
    private volatile Thread timerThread;
    private volatile Direction direction;

    private enum Direction { UP, DOWN }

    public AutoScroller(Runnable scrollUp, Runnable scrollDown,
                        IntConsumer onScrolled, Runnable setDirty) {
        this.scrollUp = scrollUp;
        this.scrollDown = scrollDown;
        this.onScrolled = onScrolled;
        this.setDirty = setDirty;
    }

    /**
     * 拖曳位置更新時呼叫。
     *
     * @param screenRow     滑鼠在螢幕的行號
     * @param contentHeight content 區域高度
     */
    public void update(int screenRow, int contentHeight) {
        if (screenRow == 0) {
            startIfNeeded(Direction.UP);
        } else if (screenRow == contentHeight - 1) {
            startIfNeeded(Direction.DOWN);
        } else {
            stop();
        }
    }

    public void stop() {
        direction = null;
        Thread t = timerThread;
        if (t != null) {
            t.interrupt();
            timerThread = null;
        }
    }

    private void startIfNeeded(Direction dir) {
        if (this.direction == dir && timerThread != null && timerThread.isAlive()) {
            return; // 同方向已在跑
        }
        stop(); // 停止舊方向
        this.direction = dir;
        timerThread = Thread.ofVirtual().name("grimo-autoscroll").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    if (dir == Direction.UP) {
                        scrollUp.run();
                        onScrolled.accept(-1);
                    } else {
                        scrollDown.run();
                        onScrolled.accept(1);
                    }
                    setDirty.run();
                    Thread.sleep(INTERVAL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "io.github.samzhu.grimo.shared.tui.AutoScrollerTest" 2>&1 | tail -5
```

Expected: all 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/tui/AutoScroller.java \
        src/test/java/io/github/samzhu/grimo/shared/tui/AutoScrollerTest.java
git commit -m "feat(tui): add AutoScroller with timer-based edge scrolling"
```
