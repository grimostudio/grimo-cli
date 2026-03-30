# TUI Framework Phase 2: View Refactoring + Building Blocks

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor all remaining Views to use the TUI framework (DisplayWidth, Layout, TuiComponent), completing the full component architecture.

**Architecture:** Phase 1 built the foundation (DisplayWidth, Layout, TuiTable, TuiStatusBar). Phase 2 adds TuiSelector and TuiMessage building blocks, refactors BannerRenderer and GrimoSlashMenuView to use them, and unifies GrimoScreen with Layout.vertical().

**Tech Stack:** Java 25, JLine 3.30.6, Phase 1 components (`shared.tui.*`), JUnit 5 + AssertJ

**Spec:** `docs/superpowers/specs/2026-03-30-tui-display-width.md`

**Depends on:** Phase 1 plan (`2026-03-30-tui-framework-phase1.md`) must be completed first.

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/io/github/samzhu/grimo/shared/tui/TuiSelector.java` | 可捲動選擇器 building block |
| Create | `src/main/java/io/github/samzhu/grimo/shared/tui/TuiMessage.java` | 對話訊息格式化 building block |
| Create | `src/test/java/io/github/samzhu/grimo/shared/tui/TuiSelectorTest.java` | TuiSelector 測試 |
| Create | `src/test/java/io/github/samzhu/grimo/shared/tui/TuiMessageTest.java` | TuiMessage 測試 |
| Modify | `src/main/java/io/github/samzhu/grimo/GrimoSlashMenuView.java:106-124` | 用 TuiSelector 替換 String.format |
| Modify | `src/main/java/io/github/samzhu/grimo/BannerRenderer.java:36-66` | 用 DisplayWidth.center() 替換 hardcoded gap |
| Modify | `src/main/java/io/github/samzhu/grimo/GrimoScreen.java:88` | 用 Layout.vertical() 替換手算 |
| Modify | 所有 View 檔案 | 加 `implements TuiComponent` |

---

### Task 6: TuiSelector + GrimoSlashMenuView 修正

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/tui/TuiSelector.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/tui/TuiSelectorTest.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoSlashMenuView.java:106-124`

- [ ] **Step 1: Write TuiSelector failing tests**

```java
package io.github.samzhu.grimo.shared.tui;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class TuiSelectorTest {

    @Test
    void shouldRenderItemsWithCorrectWidth() {
        var lines = TuiSelector.render(
                List.of("code-review", "explain-code"),
                0, 5, 40);

        assertThat(lines).hasSize(2);
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(40);
        }
    }

    @Test
    void shouldHighlightSelectedItem() {
        var lines = TuiSelector.render(
                List.of("item-a", "item-b", "item-c"),
                1, 3, 30);

        // selected item (index 1) should have different style
        assertThat(lines).hasSize(3);
        // All lines should be exactly 30 columns
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(30);
        }
    }

    @Test
    void shouldLimitVisibleItems() {
        var lines = TuiSelector.render(
                List.of("a", "b", "c", "d", "e", "f"),
                0, 3, 20);

        // maxVisible=3, so only 3 lines rendered
        assertThat(lines).hasSize(3);
    }

    @Test
    void shouldHandleEmptyList() {
        var lines = TuiSelector.render(List.of(), 0, 5, 20);
        assertThat(lines).isEmpty();
    }

    @Test
    void shouldPadNameAndDescriptionWithDisplayWidth() {
        // CJK item names should align correctly
        var lines = TuiSelector.render(
                List.of("你好", "hello"),
                0, 5, 30);

        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(30);
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.tui.TuiSelectorTest" 2>&1 | tail -10`
Expected: FAIL

- [ ] **Step 3: Implement TuiSelector**

```java
package io.github.samzhu.grimo.shared.tui;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * 可捲動選擇器渲染。
 * 每行保證 columnLength == width。
 *
 * 設計說明：
 * - 靜態方法，不持有狀態（狀態由 SlashMenuView 管理）
 * - selected item 用 BRAND_COLOR 高亮
 * - 借鑑 OpenCode InlineTool 的單行緊湊風格
 *
 * @see DisplayWidth — 寬度感知字串操作
 */
public final class TuiSelector {

    private static final int BRAND_COLOR = 67; // steel blue

    private TuiSelector() {}

    /**
     * 渲染選擇器列表。
     *
     * @param items 所有項目文字
     * @param selectedIndex 當前選中的 index
     * @param maxVisible 最多顯示幾行
     * @param width 每行的 display column 寬度
     * @return 寬度精確的 AttributedString 列表
     */
    public static List<AttributedString> render(
            List<String> items, int selectedIndex, int maxVisible, int width) {
        if (items.isEmpty()) return List.of();

        int visible = Math.min(items.size(), maxVisible);
        var lines = new ArrayList<AttributedString>(visible);

        for (int i = 0; i < visible; i++) {
            boolean active = (i == selectedIndex);
            String item = items.get(i);
            // 格式："> item..." 或 "  item..."
            String prefix = active ? "> " : "  ";
            String text = DisplayWidth.padRight(prefix + item, width);

            var sb = new AttributedStringBuilder();
            if (active) {
                sb.styled(AttributedStyle.DEFAULT.foreground(BRAND_COLOR), text);
            } else {
                sb.append(text);
            }
            lines.add(sb.toAttributedString());
        }
        return lines;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.tui.TuiSelectorTest" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Refactor GrimoSlashMenuView.render()**

In `src/main/java/io/github/samzhu/grimo/GrimoSlashMenuView.java`, add import:

```java
import io.github.samzhu.grimo.shared.tui.DisplayWidth;
```

Replace lines 110-121 (the loop inside `render()`) with:

```java
        for (int i = 0; i < visible; i++) {
            var item = filteredItems.get(i);
            boolean active = (i == selectedIndex);

            // 用 DisplayWidth 替換 String.format("%-20s")
            String name = "/" + item.name();
            String desc = item.description();
            String padName = DisplayWidth.padRight(name, 22); // "  /" + 20 chars
            String text = DisplayWidth.truncate("  " + padName + " " + desc, cols);
            text = DisplayWidth.padRight(text, cols); // 補足到 cols

            var sb = new org.jline.utils.AttributedStringBuilder();
            if (active) {
                sb.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(BRAND_COLOR), text);
            } else {
                sb.styled(org.jline.utils.AttributedStyle.DEFAULT, text);
            }
            lines.add(sb.toAttributedString());
        }
```

- [ ] **Step 6: Compile and run tests**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/tui/TuiSelector.java \
       src/test/java/io/github/samzhu/grimo/shared/tui/TuiSelectorTest.java \
       src/main/java/io/github/samzhu/grimo/GrimoSlashMenuView.java
git commit -m "feat(tui): add TuiSelector + fix GrimoSlashMenuView CJK alignment"
```

---

### Task 7: TuiMessage

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/tui/TuiMessage.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/tui/TuiMessageTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.github.samzhu.grimo.shared.tui;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TuiMessageTest {

    @Test
    void inlineShouldReturnTwoLinesWithCorrectWidth() {
        var lines = TuiMessage.inline("● ", "Skill(code-review)",
                "Successfully loaded skill", 50);
        assertThat(lines).hasSize(2);
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(50);
        }
    }

    @Test
    void inlineShouldTruncateLongTitle() {
        var lines = TuiMessage.inline("● ", "Skill(very-long-skill-name-here)",
                "loaded", 30);
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(30);
        }
    }

    @Test
    void blockUserShouldPrefixWithArrow() {
        var lines = TuiMessage.block(TuiMessage.Role.USER, "hello world", 40);
        assertThat(lines).isNotEmpty();
        // 第一行應該有 "❯" 或 "›" 前綴
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(40);
        }
    }

    @Test
    void blockAgentShouldPrefixWithDot() {
        var lines = TuiMessage.block(TuiMessage.Role.AGENT, "I can help.", 40);
        assertThat(lines).isNotEmpty();
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(40);
        }
    }

    @Test
    void blockErrorShouldPrefixWithWarning() {
        var lines = TuiMessage.block(TuiMessage.Role.ERROR, "timeout", 40);
        assertThat(lines).isNotEmpty();
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(40);
        }
    }

    @Test
    void blockShouldWrapLongContent() {
        String longText = "a".repeat(100);
        var lines = TuiMessage.block(TuiMessage.Role.AGENT, longText, 40);
        assertThat(lines.size()).isGreaterThan(1);
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(40);
        }
    }

    @Test
    void blockShouldHandleCjkContent() {
        var lines = TuiMessage.block(TuiMessage.Role.USER, "你好世界", 20);
        assertThat(lines).isNotEmpty();
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(20);
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.tui.TuiMessageTest" 2>&1 | tail -10`
Expected: FAIL

- [ ] **Step 3: Implement TuiMessage**

```java
package io.github.samzhu.grimo.shared.tui;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * 對話訊息格式化。
 * inline: 單行系統訊息（Skill loaded, Worktree created）
 * block: 多行內容（agent 回覆、使用者輸入、錯誤）
 *
 * 設計說明：
 * - 每行保證 columnLength == width
 * - inline 產生 2 行：title + detail（對齊 Claude Code 風格）
 * - block 自動 wrap，每行前綴 role icon
 * - 借鑑 OpenCode 的 InlineTool / BlockTool 兩種模式
 *
 * @see DisplayWidth — 寬度感知字串操作
 * @see <a href="https://github.com/anomalyco/opencode">OpenCode — InlineTool/BlockTool</a>
 */
public final class TuiMessage {

    private TuiMessage() {}

    public enum Role {
        USER("› ", 7),      // cyan
        AGENT("● ", 2),     // green
        ERROR("⚠ ", 1),     // red
        SYSTEM("● ", 245);  // gray

        final String icon;
        final int color;

        Role(String icon, int color) {
            this.icon = icon;
            this.color = color;
        }
    }

    /**
     * 單行系統訊息：兩行輸出。
     * Line 1: icon + title（如 "● Skill(code-review)"）
     * Line 2: "  └ " + detail（如 "  └ Successfully loaded skill"）
     */
    public static List<AttributedString> inline(
            String icon, String title, String detail, int width) {
        var lines = new ArrayList<AttributedString>(2);

        // Line 1: icon + title
        var line1 = new AttributedStringBuilder();
        line1.styled(AttributedStyle.DEFAULT.foreground(2), icon);
        String titleText = DisplayWidth.padRight(icon + title, width);
        // 重建以保證寬度：先算 icon 寬度
        line1 = new AttributedStringBuilder();
        int iconWidth = DisplayWidth.of(icon);
        String titlePart = DisplayWidth.truncate(title, width - iconWidth);
        line1.styled(AttributedStyle.DEFAULT.foreground(2), icon);
        line1.append(titlePart);
        // 補足寬度
        int remaining1 = width - DisplayWidth.of(icon) - DisplayWidth.of(titlePart);
        if (remaining1 > 0) line1.append(DisplayWidth.fill(remaining1));
        lines.add(line1.toAttributedString());

        // Line 2: "  └ " + detail
        var line2 = new AttributedStringBuilder();
        String prefix = "  └ ";
        int prefixWidth = DisplayWidth.of(prefix);
        String detailPart = DisplayWidth.truncate(detail, width - prefixWidth);
        line2.styled(AttributedStyle.DEFAULT.foreground(245), prefix + detailPart);
        int remaining2 = width - prefixWidth - DisplayWidth.of(detailPart);
        if (remaining2 > 0) line2.append(DisplayWidth.fill(remaining2));
        lines.add(line2.toAttributedString());

        return lines;
    }

    /**
     * 多行訊息：自動 wrap，每行保證寬度。
     * 第一行有 role icon，後續行用空白對齊。
     */
    public static List<AttributedString> block(
            Role role, String content, int width) {
        if (content == null || content.isBlank()) {
            // 空內容 → 一行 icon
            var sb = new AttributedStringBuilder();
            sb.styled(AttributedStyle.DEFAULT.foreground(role.color), role.icon);
            int remaining = width - DisplayWidth.of(role.icon);
            if (remaining > 0) sb.append(DisplayWidth.fill(remaining));
            return List.of(sb.toAttributedString());
        }

        int iconWidth = DisplayWidth.of(role.icon);
        int contentWidth = width - iconWidth;
        if (contentWidth <= 0) contentWidth = 1;

        // Wrap content to fit within contentWidth
        var wrappedLines = DisplayWidth.wrap(content, contentWidth);
        var lines = new ArrayList<AttributedString>(wrappedLines.size());

        for (int i = 0; i < wrappedLines.size(); i++) {
            var sb = new AttributedStringBuilder();
            String prefix = (i == 0) ? role.icon : DisplayWidth.fill(iconWidth);
            String text = wrappedLines.get(i);

            if (i == 0) {
                sb.styled(AttributedStyle.DEFAULT.foreground(role.color), prefix);
            } else {
                sb.append(prefix);
            }

            // 補足到精確寬度
            String padded = DisplayWidth.padRight(text, contentWidth);
            sb.append(padded);
            lines.add(sb.toAttributedString());
        }

        return lines;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.tui.TuiMessageTest" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/tui/TuiMessage.java \
       src/test/java/io/github/samzhu/grimo/shared/tui/TuiMessageTest.java
git commit -m "feat(tui): add TuiMessage — inline/block message formatting with width guarantee"
```

---

### Task 8: BannerRenderer 重構

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/BannerRenderer.java:36-66`

- [ ] **Step 1: Read and understand BannerRenderer.render()**

Read `src/main/java/io/github/samzhu/grimo/BannerRenderer.java` fully. The `render()` method currently:
- Uses hardcoded `gap = "        "` (8 spaces)
- Builds 5 lines: mascot left + info right, concatenated with gap
- Returns a single String

- [ ] **Step 2: Add `int cols` parameter and use DisplayWidth**

Change the method signature to accept `int cols`:

```java
public String render(String version, String agentId, String model,
                     String workspacePath, int agentCount, int skillCount,
                     int mcpCount, int taskCount, int cols) {
```

Replace hardcoded gap with dynamic calculation. Add import:

```java
import io.github.samzhu.grimo.shared.tui.DisplayWidth;
```

Replace the body: compute gap dynamically based on `cols`, mascot width (~16 columns), and info width. Use `DisplayWidth.of()` to measure.

Key change: instead of `gap = "        "`, calculate:
```java
int mascotWidth = 16; // block ghost characters
int infoMaxWidth = cols - mascotWidth - 4; // 4 for padding
String gap = DisplayWidth.fill(Math.max(2, cols - mascotWidth - infoMaxWidth));
```

Or simpler: keep relative positioning but use `DisplayWidth.padRight()` to ensure each line is exactly `cols` columns wide.

- [ ] **Step 3: Update caller in GrimoTuiRunner**

Find where `bannerRenderer.render(...)` is called in `GrimoTuiRunner.java` and add the `cols` parameter:

```java
// In GrimoTuiRunner.run(), after terminal setup:
int cols = terminal.getWidth();
String bannerText = bannerRenderer.render(
        version, agentId, model, workspacePath,
        (int) agentCount, skillCount, mcpCount, taskCount, cols);
```

Note: the existing call is around line 196 of GrimoTuiRunner.java. Read the file to find the exact location.

- [ ] **Step 4: Compile check**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/BannerRenderer.java \
       src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "feat(tui): BannerRenderer uses DisplayWidth for dynamic spacing"
```

---

### Task 9: GrimoScreen Layout.vertical() + TuiComponent 介面

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoScreen.java:88`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoContentView.java` (add implements)
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoInputView.java` (add implements)
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStatusView.java` (add implements)
- Modify: `src/main/java/io/github/samzhu/grimo/BannerRenderer.java` (add implements)

- [ ] **Step 1: Update GrimoScreen to use Layout.vertical()**

In `src/main/java/io/github/samzhu/grimo/GrimoScreen.java`, add import:

```java
import io.github.samzhu.grimo.shared.tui.Layout;
```

Replace line 88:

Current:
```java
int contentHeight = Math.max(1, rows - INPUT_HEIGHT - STATUS_HEIGHT);
```

Replace with:
```java
// 設計說明：使用 Layout.vertical() 替換手算，與 TUI framework 一致
// 借鑑 Ratatui Layout + OpenCode flexGrow
int[] heights = Layout.vertical(rows, 0,
        new Layout.Fill(),                    // content（填滿剩餘）
        new Layout.Fixed(INPUT_HEIGHT),       // input（固定 3 行）
        new Layout.Fixed(STATUS_HEIGHT));     // status（固定 1 行）
int contentHeight = Math.max(1, heights[0]);
```

- [ ] **Step 2: Add TuiComponent to GrimoStatusView**

In `src/main/java/io/github/samzhu/grimo/GrimoStatusView.java`:

Add import:
```java
import io.github.samzhu.grimo.shared.tui.TuiComponent;
```

Change class declaration:
```java
public class GrimoStatusView implements TuiComponent {
```

The existing `render(int cols)` method already returns `List<AttributedString>`, matching the `TuiComponent.render(int width)` contract.

- [ ] **Step 3: Add TuiComponent to GrimoContentView**

Same pattern — add import, add `implements TuiComponent`.

Note: GrimoContentView has `render(int cols, int rows)` (2 parameters), not matching `TuiComponent.render(int width)`. Add an adapter method:

```java
@Override
public List<AttributedString> render(int width) {
    // 容器模式：不限高度，回傳所有內容
    // GrimoScreen 用 render(cols, contentHeight) 取可見切片
    return render(width, Integer.MAX_VALUE);
}
```

- [ ] **Step 4: Add TuiComponent to GrimoInputView**

Same pattern. GrimoInputView has `render(int cols)` returning `List<AttributedString>` — already matches.

- [ ] **Step 5: Compile and verify**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.tui.*" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL — all TUI tests pass

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoScreen.java \
       src/main/java/io/github/samzhu/grimo/GrimoContentView.java \
       src/main/java/io/github/samzhu/grimo/GrimoInputView.java \
       src/main/java/io/github/samzhu/grimo/GrimoStatusView.java \
       src/main/java/io/github/samzhu/grimo/BannerRenderer.java
git commit -m "feat(tui): GrimoScreen uses Layout.vertical() + all Views implement TuiComponent"
```

---

### Task 10: Glossary Update (Phase 2 terms)

**Files:**
- Modify: `docs/glossary.md`

- [ ] **Step 1: Add Phase 2 terms**

Add to the TUI 框架術語 section:

```markdown
| **TuiSelector** | TUI Selector | 可捲動選擇器。渲染 selected/unselected 項目列表，每行保證精確寬度。用於 slash menu、agent 選擇。 |
| **TuiMessage** | TUI Message | 對話訊息格式化。inline 模式（2 行 icon+detail）和 block 模式（多行 wrap + role icon）。借鑑 OpenCode InlineTool/BlockTool。 |
```

- [ ] **Step 2: Commit**

```bash
git add docs/glossary.md
git commit -m "docs: add TuiSelector and TuiMessage to glossary"
```
