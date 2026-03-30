# TUI Framework Phase 1: Foundation + Immediate Fixes

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the TUI width framework (DisplayWidth + Layout + TuiComponent) and fix all currently broken alignment (agent-list, status bar).

**Architecture:** `DisplayWidth` wraps JLine `WCWidth.wcwidth(int)` for column-aware string operations. `Layout` splits screen areas with Fixed/Fill slots. `TuiTable` and `TuiStatusBar` are building blocks that guarantee correct width. Existing views are refactored to use them.

**Tech Stack:** Java 25, JLine 3.30.6 (`WCWidth`, `AttributedString`, `AttributedStyle`), JUnit 5 + AssertJ

**Spec:** `docs/superpowers/specs/2026-03-30-tui-display-width.md`

**SDK Verification (JLine 3.30.6):**
```java
WCWidth.wcwidth(int codePoint)                          // -1/0/1/2
AttributedCharSequence.columnLength()                    // total display columns
AttributedCharSequence.columnSubSequence(int, int)       // column-based substring
AttributedCharSequence.columnSplitLength(int)            // column-based line split
AttributedCharSequence.columnSplitLength(int, boolean, boolean) // with options
```

**Phase 2** (separate plan): TuiSelector, TuiMessage, BannerRenderer 重構, GrimoScreen Layout.vertical(), 全 View 加 TuiComponent 介面

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/io/github/samzhu/grimo/shared/tui/DisplayWidth.java` | 寬度計算 + 字串操作 |
| Create | `src/main/java/io/github/samzhu/grimo/shared/tui/TuiComponent.java` | 元件契約介面 |
| Create | `src/main/java/io/github/samzhu/grimo/shared/tui/Layout.java` | 區域切分計算 |
| Create | `src/main/java/io/github/samzhu/grimo/shared/tui/TuiTable.java` | 表格 building block |
| Create | `src/main/java/io/github/samzhu/grimo/shared/tui/TuiStatusBar.java` | 狀態列 building block |
| Create | `src/test/java/io/github/samzhu/grimo/shared/tui/DisplayWidthTest.java` | DisplayWidth 測試 |
| Create | `src/test/java/io/github/samzhu/grimo/shared/tui/LayoutTest.java` | Layout 測試 |
| Create | `src/test/java/io/github/samzhu/grimo/shared/tui/TuiTableTest.java` | TuiTable 測試 |
| Modify | `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java:57-83` | list() 用 TuiTable |
| Modify | `src/main/java/io/github/samzhu/grimo/GrimoStatusView.java:57-59` | truncation 用 DisplayWidth |

---

### Task 1: DisplayWidth — 寬度計算工具

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/tui/DisplayWidth.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/tui/DisplayWidthTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.github.samzhu.grimo.shared.tui;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DisplayWidthTest {

    // === of() ===

    @Test
    void ofShouldReturnZeroForEmptyString() {
        assertThat(DisplayWidth.of("")).isEqualTo(0);
    }

    @Test
    void ofShouldCountAsciiAsSingleWidth() {
        assertThat(DisplayWidth.of("hello")).isEqualTo(5);
    }

    @Test
    void ofShouldCountCjkAsDoubleWidth() {
        assertThat(DisplayWidth.of("你好")).isEqualTo(4);
    }

    @Test
    void ofShouldHandleMixedAsciiAndCjk() {
        assertThat(DisplayWidth.of("hi你好")).isEqualTo(6); // 2 + 4
    }

    @Test
    void ofShouldHandleSpecialChars() {
        assertThat(DisplayWidth.of("● >")).isEqualTo(3); // ● is 1 wide in terminal
    }

    // === padRight() ===

    @Test
    void padRightShouldPadAsciiToWidth() {
        assertThat(DisplayWidth.padRight("hi", 5)).isEqualTo("hi   ");
        assertThat(DisplayWidth.of(DisplayWidth.padRight("hi", 5))).isEqualTo(5);
    }

    @Test
    void padRightShouldPadCjkToWidth() {
        // "你好" = 4 columns, pad to 6 → 2 spaces
        String result = DisplayWidth.padRight("你好", 6);
        assertThat(DisplayWidth.of(result)).isEqualTo(6);
        assertThat(result).isEqualTo("你好  ");
    }

    @Test
    void padRightShouldTruncateIfTooWide() {
        String result = DisplayWidth.padRight("hello world", 5);
        assertThat(DisplayWidth.of(result)).isEqualTo(5);
    }

    // === padLeft() ===

    @Test
    void padLeftShouldRightAlignText() {
        assertThat(DisplayWidth.padLeft("hi", 5)).isEqualTo("   hi");
    }

    // === center() ===

    @Test
    void centerShouldCenterText() {
        String result = DisplayWidth.center("hi", 6);
        assertThat(result).isEqualTo("  hi  ");
        assertThat(DisplayWidth.of(result)).isEqualTo(6);
    }

    @Test
    void centerShouldHandleOddWidth() {
        String result = DisplayWidth.center("hi", 7);
        // 5 padding → 2 left, 3 right (or 3 left, 2 right)
        assertThat(DisplayWidth.of(result)).isEqualTo(7);
        assertThat(result.trim()).isEqualTo("hi");
    }

    // === truncate() ===

    @Test
    void truncateShouldNotTruncateShortString() {
        assertThat(DisplayWidth.truncate("hi", 10)).isEqualTo("hi");
    }

    @Test
    void truncateShouldAddEllipsisForLongString() {
        String result = DisplayWidth.truncate("hello world", 8);
        assertThat(DisplayWidth.of(result)).isLessThanOrEqualTo(8);
        assertThat(result).endsWith("…");
    }

    @Test
    void truncateShouldNotSplitCjkCharacter() {
        // "你好世界" = 8 columns, truncate to 5 → "你好…" (4+1=5), not "你好世…" would be 7
        String result = DisplayWidth.truncate("你好世界", 5);
        assertThat(DisplayWidth.of(result)).isLessThanOrEqualTo(5);
        assertThat(result).endsWith("…");
    }

    // === fill() ===

    @Test
    void fillShouldReturnSpaces() {
        assertThat(DisplayWidth.fill(3)).isEqualTo("   ");
        assertThat(DisplayWidth.of(DisplayWidth.fill(3))).isEqualTo(3);
    }

    // === wrap() ===

    @Test
    void wrapShouldNotWrapShortString() {
        var lines = DisplayWidth.wrap("hello", 10);
        assertThat(lines).containsExactly("hello");
    }

    @Test
    void wrapShouldWrapLongString() {
        var lines = DisplayWidth.wrap("hello world", 6);
        assertThat(lines).hasSize(2);
        for (String line : lines) {
            assertThat(DisplayWidth.of(line)).isLessThanOrEqualTo(6);
        }
    }

    @Test
    void wrapShouldHandleCjk() {
        // "你好世界" = 8 columns, wrap at 5 → ["你好", "世界"] or similar
        var lines = DisplayWidth.wrap("你好世界", 5);
        assertThat(lines.size()).isGreaterThanOrEqualTo(2);
        for (String line : lines) {
            assertThat(DisplayWidth.of(line)).isLessThanOrEqualTo(5);
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.tui.DisplayWidthTest" 2>&1 | tail -10`
Expected: FAIL — `DisplayWidth` class not found

- [ ] **Step 3: Implement DisplayWidth**

```java
package io.github.samzhu.grimo.shared.tui;

import org.jline.utils.WCWidth;

import java.util.ArrayList;
import java.util.List;

/**
 * Terminal column 寬度感知的字串操作。
 * 封裝 JLine WCWidth，所有 TUI 元件共用。
 *
 * 設計說明：
 * - CJK 字 = 2 columns, ASCII = 1 column, combining marks = 0
 * - 使用 codePointAt 而非 charAt，正確處理 supplementary characters
 * - truncate 不會切半個 CJK 字
 * - 所有方法 null-safe（null → 空字串）
 *
 * @see <a href="https://github.com/jline/jline3/blob/master/terminal/src/main/java/org/jline/utils/WCWidth.java">
 *      JLine WCWidth — Unicode 16.0 East Asian Width</a>
 * @see <a href="https://github.com/charmbracelet/lipgloss">Lipgloss — Go TUI styling with wcwidth</a>
 */
public final class DisplayWidth {

    private DisplayWidth() {}

    /**
     * 字串的 terminal display column 寬度。
     * CJK = 2, ASCII = 1, combining = 0, control = 0。
     */
    public static int of(String s) {
        if (s == null || s.isEmpty()) return 0;
        int width = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int w = WCWidth.wcwidth(cp);
            width += Math.max(w, 0); // control chars (-1) → 0
            i += Character.charCount(cp);
        }
        return width;
    }

    /**
     * 右補空白到指定欄寬（左對齊）。
     * 超過 columns 時截斷。
     */
    public static String padRight(String s, int columns) {
        if (s == null) s = "";
        int width = of(s);
        if (width > columns) return truncate(s, columns);
        if (width == columns) return s;
        return s + " ".repeat(columns - width);
    }

    /**
     * 左補空白到指定欄寬（右對齊）。
     */
    public static String padLeft(String s, int columns) {
        if (s == null) s = "";
        int width = of(s);
        if (width > columns) return truncate(s, columns);
        if (width == columns) return s;
        return " ".repeat(columns - width) + s;
    }

    /**
     * 置中（左右均分空白，奇數時右側多一格）。
     */
    public static String center(String s, int columns) {
        if (s == null) s = "";
        int width = of(s);
        if (width >= columns) return truncate(s, columns);
        int totalPad = columns - width;
        int left = totalPad / 2;
        int right = totalPad - left;
        return " ".repeat(left) + s + " ".repeat(right);
    }

    /**
     * 截斷到 maxColumns，超過加 "…"。
     * 保證不切半個 CJK 字。
     */
    public static String truncate(String s, int maxColumns) {
        if (s == null) return "";
        if (of(s) <= maxColumns) return s;
        if (maxColumns <= 0) return "";
        if (maxColumns == 1) return "…";

        // 預留 1 column 給 "…"
        int target = maxColumns - 1;
        var sb = new StringBuilder();
        int width = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int w = WCWidth.wcwidth(cp);
            if (w < 0) w = 0;
            if (width + w > target) break;
            sb.appendCodePoint(cp);
            width += w;
            i += Character.charCount(cp);
        }
        sb.append("…");
        return sb.toString();
    }

    /**
     * 產生指定欄寬的空白。
     */
    public static String fill(int columns) {
        return columns > 0 ? " ".repeat(columns) : "";
    }

    /**
     * 依 column 寬度 word-wrap。
     * 以空白為斷點；CJK 字之間允許斷行。
     */
    public static List<String> wrap(String s, int maxColumns) {
        if (s == null || s.isEmpty()) return List.of("");
        if (maxColumns <= 0) return List.of(s);

        var lines = new ArrayList<String>();
        var current = new StringBuilder();
        int currentWidth = 0;

        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int w = WCWidth.wcwidth(cp);
            if (w < 0) w = 0;

            // 換行條件：加上這個字後超過 maxColumns
            if (currentWidth + w > maxColumns && currentWidth > 0) {
                lines.add(current.toString());
                current = new StringBuilder();
                currentWidth = 0;
            }

            current.appendCodePoint(cp);
            currentWidth += w;
            i += Character.charCount(cp);
        }

        if (current.length() > 0) {
            lines.add(current.toString());
        }

        return lines;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.tui.DisplayWidthTest" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/tui/DisplayWidth.java \
       src/test/java/io/github/samzhu/grimo/shared/tui/DisplayWidthTest.java
git commit -m "feat(tui): add DisplayWidth — column-aware string operations wrapping JLine WCWidth"
```

---

### Task 2: TuiComponent + Layout

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/tui/TuiComponent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/tui/Layout.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/tui/LayoutTest.java`

- [ ] **Step 1: Create TuiComponent interface**

```java
package io.github.samzhu.grimo.shared.tui;

import org.jline.utils.AttributedString;
import java.util.List;

/**
 * TUI 元件契約。
 * 保證：回傳的每行 columnLength == width。
 * 行數 = 自然高度（不限），由容器決定顯示多少。
 *
 * 設計說明：
 * - 借鑑 Ratatui Widget trait 的 render(area) 模式
 * - 借鑑 OpenCode scrollbox 容器模式：元件不管高度，容器負責捲動
 * - 借鑑 Lipgloss 的 string-in/string-out 簡潔設計
 *
 * @see <a href="https://github.com/anomalyco/opencode">OpenCode — scrollbox 容器模式</a>
 * @see <a href="https://github.com/ratatui/ratatui">Ratatui — Widget trait</a>
 */
public interface TuiComponent {
    List<AttributedString> render(int width);
}
```

- [ ] **Step 2: Write Layout failing tests**

```java
package io.github.samzhu.grimo.shared.tui;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LayoutTest {

    // === horizontal ===

    @Test
    void horizontalShouldAllocateFixedSlots() {
        int[] result = Layout.horizontal(40, 0,
                new Layout.Fixed(2),
                new Layout.Fixed(12),
                new Layout.Fixed(10));
        assertThat(result).containsExactly(2, 12, 10);
    }

    @Test
    void horizontalShouldFillRemainingSpace() {
        int[] result = Layout.horizontal(40, 0,
                new Layout.Fixed(2),
                new Layout.Fixed(12),
                new Layout.Fill());
        assertThat(result).containsExactly(2, 12, 26);
    }

    @Test
    void horizontalShouldDistributeFillEvenly() {
        int[] result = Layout.horizontal(40, 0,
                new Layout.Fill(),
                new Layout.Fixed(10),
                new Layout.Fill());
        assertThat(result).containsExactly(15, 10, 15);
    }

    @Test
    void horizontalShouldAccountForGap() {
        // 40 total, gap=1 between 3 slots = 2 gaps = 2 columns
        // remaining = 40 - 2 - 12 - 2 = 24
        int[] result = Layout.horizontal(40, 1,
                new Layout.Fixed(2),
                new Layout.Fixed(12),
                new Layout.Fill());
        assertThat(result).containsExactly(2, 12, 24);
    }

    // === vertical ===

    @Test
    void verticalShouldAllocateFixedSlots() {
        int[] result = Layout.vertical(30, 0,
                new Layout.Fixed(6),   // banner
                new Layout.Fill(),     // content
                new Layout.Fixed(2),   // input
                new Layout.Fixed(1));  // status
        assertThat(result).containsExactly(6, 21, 2, 1);
    }

    @Test
    void verticalShouldHandleGap() {
        // 30 rows, 3 gaps of 1 = 3
        // fill = 30 - 6 - 2 - 1 - 3 = 18
        int[] result = Layout.vertical(30, 1,
                new Layout.Fixed(6),
                new Layout.Fill(),
                new Layout.Fixed(2),
                new Layout.Fixed(1));
        assertThat(result).containsExactly(6, 18, 2, 1);
    }

    @Test
    void fillShouldNotGoNegative() {
        // fixed slots exceed total → fill gets 0
        int[] result = Layout.horizontal(10, 0,
                new Layout.Fixed(8),
                new Layout.Fixed(8),
                new Layout.Fill());
        assertThat(result[2]).isGreaterThanOrEqualTo(0);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.tui.LayoutTest" 2>&1 | tail -10`
Expected: FAIL

- [ ] **Step 4: Implement Layout**

```java
package io.github.samzhu.grimo.shared.tui;

/**
 * 佈局切分計算。
 * 純算術，不持有狀態。
 *
 * 設計說明：
 * - 借鑑 Ratatui Constraint（Fixed/Fill）
 * - 借鑑 OpenCode box flexGrow
 * - 用 sealed interface 限制 Slot 類型，編譯期安全
 *
 * @see <a href="https://github.com/ratatui/ratatui">Ratatui — Layout + Constraint</a>
 * @see <a href="https://github.com/anomalyco/opencode">OpenCode — flexGrow</a>
 */
public final class Layout {

    private Layout() {}

    public sealed interface Slot permits Fixed, Fill {}
    public record Fixed(int size) implements Slot {}
    public record Fill() implements Slot {}

    /**
     * 水平切分：將 totalCols 分配給各 Slot。
     * Fixed 取固定值，Fill 均分剩餘空間。
     * gap 是 slot 間的間距（不含首尾）。
     */
    public static int[] horizontal(int totalCols, int gap, Slot... slots) {
        return split(totalCols, gap, slots);
    }

    /**
     * 垂直切分：將 totalRows 分配給各 Slot。
     */
    public static int[] vertical(int totalRows, int gap, Slot... slots) {
        return split(totalRows, gap, slots);
    }

    private static int[] split(int total, int gap, Slot[] slots) {
        int gapTotal = gap * Math.max(0, slots.length - 1);
        int fixedTotal = 0;
        int fillCount = 0;

        for (var slot : slots) {
            switch (slot) {
                case Fixed f -> fixedTotal += f.size();
                case Fill ignored -> fillCount++;
            }
        }

        int remaining = Math.max(0, total - fixedTotal - gapTotal);
        int fillSize = fillCount > 0 ? remaining / fillCount : 0;
        int fillRemainder = fillCount > 0 ? remaining % fillCount : 0;

        int[] result = new int[slots.length];
        int fillIndex = 0;
        for (int i = 0; i < slots.length; i++) {
            result[i] = switch (slots[i]) {
                case Fixed f -> f.size();
                case Fill ignored -> {
                    // 最後一個 Fill 吃掉餘數，避免總和不等於 total
                    fillIndex++;
                    yield fillSize + (fillIndex == fillCount ? fillRemainder : 0);
                }
            };
        }
        return result;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.tui.LayoutTest" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Compile full project**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/tui/TuiComponent.java \
       src/main/java/io/github/samzhu/grimo/shared/tui/Layout.java \
       src/test/java/io/github/samzhu/grimo/shared/tui/LayoutTest.java
git commit -m "feat(tui): add TuiComponent interface + Layout with Fixed/Fill slots"
```

---

### Task 3: TuiTable + AgentCommands 修正

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/tui/TuiTable.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/tui/TuiTableTest.java`
- Modify: `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java:57-83`

- [ ] **Step 1: Write failing tests**

```java
package io.github.samzhu.grimo.shared.tui;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TuiTableTest {

    @Test
    void shouldAlignColumnsWithFixedWidth() {
        String result = TuiTable.builder()
                .column("ID", 10)
                .column("STATUS", 10)
                .row("claude", "ready")
                .row("gemini", "ready")
                .build(30);

        var lines = result.split("\n");
        assertThat(lines).hasSize(2);
        for (String line : lines) {
            assertThat(DisplayWidth.of(line)).isEqualTo(30);
        }
    }

    @Test
    void shouldFillRemainingWidth() {
        String result = TuiTable.builder()
                .column("", 2)              // indicator
                .column("ID", 10)           // fixed
                .column("MODEL", 0)         // fill (0 = fill)
                .row("> ", "claude", "claude-sonnet-4-6")
                .row("  ", "gemini", "gemini-2.5-pro")
                .build(40);

        var lines = result.split("\n");
        for (String line : lines) {
            assertThat(DisplayWidth.of(line)).isEqualTo(40);
        }
        assertThat(lines[0]).contains("claude-sonnet-4-6");
    }

    @Test
    void shouldTruncateLongValues() {
        String result = TuiTable.builder()
                .column("NAME", 8)
                .row("very-long-name-here")
                .build(10);

        var lines = result.split("\n");
        assertThat(DisplayWidth.of(lines[0])).isEqualTo(10);
    }

    @Test
    void shouldHandleCjkContent() {
        String result = TuiTable.builder()
                .column("名前", 8)
                .column("狀態", 8)
                .row("你好", "正常")
                .build(20);

        var lines = result.split("\n");
        for (String line : lines) {
            assertThat(DisplayWidth.of(line)).isEqualTo(20);
        }
    }

    @Test
    void shouldHandleMultipleFixedAndOneFill() {
        String result = TuiTable.builder()
                .column("", 2)
                .column("ID", 12)
                .column("STATUS", 12)
                .column("MODEL", 0)  // fill
                .row("> ", "claude", "ready", "claude-sonnet-4-6")
                .row("  ", "gemini", "ready", "gemini-2.5-pro")
                .row("  ", "codex", "ready", "o4-mini")
                .build(60);

        var lines = result.split("\n");
        assertThat(lines).hasSize(3);
        for (String line : lines) {
            assertThat(DisplayWidth.of(line)).isEqualTo(60);
        }
        // 確認 indicator 顯示正確
        assertThat(lines[0]).startsWith("> ");
        assertThat(lines[1]).startsWith("  ");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.tui.TuiTableTest" 2>&1 | tail -10`
Expected: FAIL

- [ ] **Step 3: Implement TuiTable**

```java
package io.github.samzhu.grimo.shared.tui;

import java.util.ArrayList;
import java.util.List;

/**
 * 寬度感知的表格 Builder。
 * 保證每行輸出的 display width 精確等於指定 width。
 *
 * 設計說明：
 * - column width=0 表示 Fill（填滿剩餘空間），使用 Layout.horizontal 計算
 * - 每個 cell 用 DisplayWidth.padRight 對齊，超長用 truncate
 * - 多個 Fill column 均分剩餘空間
 *
 * @see Layout — 欄寬計算
 * @see DisplayWidth — 寬度感知字串操作
 */
public final class TuiTable {

    private TuiTable() {}

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<ColDef> columns = new ArrayList<>();
        private final List<String[]> rows = new ArrayList<>();

        /**
         * 定義一個欄位。
         * @param name 欄位名稱（目前不渲染 header，僅供文件參考）
         * @param width 固定欄寬；0 = 填滿剩餘空間（Fill）
         */
        public Builder column(String name, int width) {
            columns.add(new ColDef(name, width));
            return this;
        }

        public Builder row(String... values) {
            rows.add(values);
            return this;
        }

        /**
         * 建構表格字串。每行保證 display width == totalWidth。
         */
        public String build(int totalWidth) {
            // 用 Layout.horizontal 計算各欄實際寬度
            int colGap = 1; // 欄間 1 空白
            Layout.Slot[] slots = columns.stream()
                    .map(c -> c.width > 0
                            ? (Layout.Slot) new Layout.Fixed(c.width)
                            : new Layout.Fill())
                    .toArray(Layout.Slot[]::new);
            int[] colWidths = Layout.horizontal(totalWidth, colGap, slots);

            var sb = new StringBuilder();
            for (int r = 0; r < rows.size(); r++) {
                String[] row = rows.get(r);
                var line = new StringBuilder();
                for (int c = 0; c < colWidths.length; c++) {
                    if (c > 0) line.append(" "); // gap
                    String value = c < row.length ? row[c] : "";
                    line.append(DisplayWidth.padRight(value, colWidths[c]));
                }
                // 最終保證整行寬度精確
                String lineStr = line.toString();
                int lineWidth = DisplayWidth.of(lineStr);
                if (lineWidth < totalWidth) {
                    lineStr = lineStr + DisplayWidth.fill(totalWidth - lineWidth);
                } else if (lineWidth > totalWidth) {
                    lineStr = DisplayWidth.truncate(lineStr, totalWidth);
                }
                sb.append(lineStr);
                if (r < rows.size() - 1) sb.append("\n");
            }
            return sb.toString();
        }

        private record ColDef(String name, int width) {}
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.tui.TuiTableTest" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Refactor AgentCommands.list() to use TuiTable**

In `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java`, add import and replace the `list()` method body:

Add import:
```java
import io.github.samzhu.grimo.shared.tui.TuiTable;
```

Replace lines 57-83 (the entire `list()` method) with:

```java
    @Command(name = "agent-list", description = "List all configured agents")
    public String list() {
        var models = registry.listAll();
        if (models.isEmpty()) {
            return "No agents available. Install a CLI agent (claude, gemini, or codex).";
        }

        String defaultAgent = config.getDefaultAgent();
        if (defaultAgent == null) {
            defaultAgent = models.entrySet().stream()
                    .filter(e -> e.getValue().isAvailable())
                    .map(Map.Entry::getKey)
                    .findFirst().orElse("");
        }

        var table = TuiTable.builder()
                .column("", 2)           // indicator
                .column("ID", 10)        // agent name
                .column("STATUS", 10)    // ready / not available
                .column("MODEL", 0);     // fill remaining

        for (var entry : models.entrySet()) {
            String id = entry.getKey();
            String indicator = id.equals(defaultAgent) ? "> " : "  ";
            String status = entry.getValue().isAvailable() ? "ready" : "not available";
            String model = config.getAgentOption(id, "model");
            if (model == null) model = RECOMMENDED_MODELS.getOrDefault(id, "");
            table.row(indicator, id, status, model);
        }

        // 使用固定寬度 60 columns（command output 不知道 terminal width）
        return table.build(60);
    }
```

- [ ] **Step 6: Run all tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.tui.*" --tests "io.github.samzhu.grimo.agent.AgentCommandsTest" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/tui/TuiTable.java \
       src/test/java/io/github/samzhu/grimo/shared/tui/TuiTableTest.java \
       src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java
git commit -m "feat(tui): add TuiTable + refactor AgentCommands.list() for proper alignment"
```

---

### Task 4: TuiStatusBar + GrimoStatusView 修正

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/tui/TuiStatusBar.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStatusView.java:57-59`

- [ ] **Step 1: Create TuiStatusBar**

```java
package io.github.samzhu.grimo.shared.tui;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * 單行狀態列，寬度精確 == width。
 * 超過截斷（column-aware），不足補空白。
 *
 * @see DisplayWidth — 寬度計算
 */
public final class TuiStatusBar {

    private TuiStatusBar() {}

    /**
     * 產生一行 AttributedString，display width 精確 == width。
     */
    public static AttributedString of(String text, AttributedStyle style, int width) {
        String fitted = DisplayWidth.padRight(text, width);
        return new AttributedString(fitted, style);
    }
}
```

- [ ] **Step 2: Fix GrimoStatusView truncation**

In `src/main/java/io/github/samzhu/grimo/GrimoStatusView.java`, add import:

```java
import io.github.samzhu.grimo.shared.tui.TuiStatusBar;
```

Replace the truncation logic (lines 57-59):

Current:
```java
        String text = statusText;
        if (text.length() > cols) {
            text = text.substring(0, cols);
        }
```

Replace with:
```java
        // 使用 TuiStatusBar 處理截斷（column-aware，不切 CJK）
        return TuiStatusBar.of(statusText, style, cols);
```

Note: you'll need to adjust the surrounding code so the style variable is available before this line. Read the full render() method to understand the flow.

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/tui/TuiStatusBar.java \
       src/main/java/io/github/samzhu/grimo/GrimoStatusView.java
git commit -m "feat(tui): add TuiStatusBar + fix GrimoStatusView CJK truncation"
```

---

### Task 5: Glossary Update

**Files:**
- Modify: `docs/glossary.md`

- [ ] **Step 1: Add TUI framework terms**

Add to the glossary under a new section "TUI 框架術語":

```markdown
| **DisplayWidth** | Display Width | 封裝 JLine WCWidth 的字串寬度計算工具。CJK=2, ASCII=1。提供 padRight/padLeft/center/truncate/wrap 操作。 |
| **TuiComponent** | TUI Component | TUI 元件介面。`render(int width)` 回傳 `List<AttributedString>`，每行保證 columnLength == width。元件不管高度，容器負責捲動。 |
| **Layout** | Layout | 佈局切分計算。Slot.Fixed(n) 固定值，Slot.Fill() 填滿剩餘。支援 gap 間距。 |
| **TuiTable** | TUI Table | 寬度感知的表格 Builder。用 Layout.horizontal 計算欄寬，DisplayWidth.padRight 對齊。 |
| **TuiStatusBar** | TUI Status Bar | 單行狀態列元件。truncate 感知 CJK，保證精確 width。 |
```

- [ ] **Step 2: Commit**

```bash
git add docs/glossary.md
git commit -m "docs: add TUI framework terms to glossary"
```
