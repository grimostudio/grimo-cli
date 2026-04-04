# ContentView Line Invariant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Guarantee the input cursor always stays on the `❯` prompt line by ensuring every `AttributedString` in `ContentView.lines` contains no embedded `\n`.

**Architecture:** Fix 3 broken methods in `ContentView.java` to split multi-line text on `\n` (same pattern already used by `appendCommandOutput()`). Fix `removeLastLine()` to sync `wrappedCache`. Add unit tests to enforce the invariant.

**Tech Stack:** Java 25, JLine 3.30.6 (`AttributedString`, `AttributedStringBuilder`), JUnit 5 + AssertJ

**Spec:** `docs/superpowers/specs/2026-04-04-contentview-line-invariant-design.md`

---

### Task 1: Create `ContentViewTest` with failing tests for the invariant

**Files:**
- Create: `src/test/java/io/github/samzhu/grimo/tui/view/ContentViewTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package io.github.samzhu.grimo.tui.view;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentViewTest {

    // --- appendAiReply: \n split ---

    @Test
    void appendAiReply_splitsNewlines_noRenderedLineContainsNewline() {
        var cv = new ContentView();
        cv.appendAiReply("line1\nline2\nline3");
        var rendered = cv.render(80, 10);
        for (var line : rendered) {
            assertThat(line.toString()).doesNotContain("\n");
        }
    }

    @Test
    void appendAiReply_multiLine_firstLineHasBulletPrefix() {
        var cv = new ContentView();
        cv.appendAiReply("hello\nworld");
        // render with enough height to see all content (bottom-aligned)
        var rendered = cv.render(80, 20);
        // Find non-empty lines from bottom (bottom-aligned rendering)
        var nonEmpty = rendered.stream()
                .filter(l -> !l.toString().isBlank())
                .toList();
        // Should have at least 2 non-empty lines (split on \n)
        assertThat(nonEmpty).hasSizeGreaterThanOrEqualTo(2);
        // First content line should start with ⏺ (the brand bullet)
        assertThat(nonEmpty.getFirst().toString()).startsWith("⏺ hello");
        // Second content line should have continuation indent
        assertThat(nonEmpty.get(1).toString()).startsWith("  world");
    }

    @Test
    void appendAiReply_trailingNewline_preservesEmptyLine() {
        var cv = new ContentView();
        cv.appendAiReply("hello\n");
        var rendered = cv.render(80, 10);
        for (var line : rendered) {
            assertThat(line.toString()).doesNotContain("\n");
        }
    }

    @Test
    void appendAiReply_singleLine_behaviorUnchanged() {
        var cv = new ContentView();
        cv.appendAiReply("just one line");
        var rendered = cv.render(80, 10);
        var nonEmpty = rendered.stream()
                .filter(l -> !l.toString().isBlank())
                .toList();
        assertThat(nonEmpty.getFirst().toString()).startsWith("⏺ just one line");
    }

    // --- appendError: \n split ---

    @Test
    void appendError_splitsNewlines_noRenderedLineContainsNewline() {
        var cv = new ContentView();
        cv.appendError("error line1\nerror line2");
        var rendered = cv.render(80, 10);
        for (var line : rendered) {
            assertThat(line.toString()).doesNotContain("\n");
        }
    }

    @Test
    void appendError_multiLine_firstLineHasWarningPrefix() {
        var cv = new ContentView();
        cv.appendError("bad\nthing");
        var rendered = cv.render(80, 20);
        var nonEmpty = rendered.stream()
                .filter(l -> !l.toString().isBlank())
                .toList();
        assertThat(nonEmpty).hasSizeGreaterThanOrEqualTo(2);
        assertThat(nonEmpty.getFirst().toString()).startsWith("⚠ bad");
        assertThat(nonEmpty.get(1).toString()).startsWith("  thing");
    }

    // --- appendLine: defensive \n check ---

    @Test
    void appendLine_withEmbeddedNewline_splits() {
        var cv = new ContentView();
        var styled = new AttributedString("styled\ntext",
                AttributedStyle.DEFAULT.foreground(245));
        cv.appendLine(styled);
        var rendered = cv.render(80, 10);
        for (var line : rendered) {
            assertThat(line.toString()).doesNotContain("\n");
        }
    }

    @Test
    void appendLine_withoutNewline_addsDirectly() {
        var cv = new ContentView();
        var plain = new AttributedString("no newline here",
                AttributedStyle.DEFAULT.foreground(245));
        cv.appendLine(plain);
        var rendered = cv.render(80, 10);
        var nonEmpty = rendered.stream()
                .filter(l -> !l.toString().isBlank())
                .toList();
        assertThat(nonEmpty).anyMatch(l -> l.toString().contains("no newline here"));
    }

    // --- removeLastLine: wrappedCache sync ---

    @Test
    void removeLastLine_syncsWrappedCache() {
        var cv = new ContentView();
        cv.appendAiReply("first");
        cv.appendLine(new AttributedString("temporary"));
        // Trigger cache build by calling getBufferLines
        var beforeRemove = cv.getBufferLines(80);
        int sizeBefore = beforeRemove.size();

        cv.removeLastLine();
        var afterRemove = cv.getBufferLines(80);

        // Should have one fewer logical line in the cache
        assertThat(afterRemove.size()).isLessThan(sizeBefore);
    }

    @Test
    void removeLastLine_wideLine_removesAllWrappedEntries() {
        var cv = new ContentView();
        // Add a line wider than 20 cols → will wrap in cache
        cv.appendLine(new AttributedString("A".repeat(50)));
        // Trigger cache build at 20 cols → wraps to 3 entries (50/20=2.5 → 3 parts)
        var before = cv.getBufferLines(20);
        int sizeBefore = before.size();

        cv.removeLastLine();
        var after = cv.getBufferLines(20);

        // All wrapped entries for that line should be gone
        assertThat(after.size()).isEqualTo(sizeBefore - 3);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.tui.view.ContentViewTest" -i 2>&1 | tail -30`

Expected: Multiple failures — `appendAiReply_splitsNewlines_noRenderedLineContainsNewline` should fail because the current `appendAiReply` does not split `\n`. `removeLastLine_syncsWrappedCache` should fail because `wrappedCache` is not synced.

- [ ] **Step 3: Commit the test file**

```bash
git add src/test/java/io/github/samzhu/grimo/tui/view/ContentViewTest.java
git commit -m "test: add ContentView line invariant tests (red)"
```

---

### Task 2: Fix `appendAiReply()` — split multi-line text

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/tui/view/ContentView.java:65-74`

- [ ] **Step 1: Replace `appendAiReply` method**

Replace lines 65-74 of `ContentView.java` with:

```java
    /**
     * 附加 AI 回覆：⏺ 前綴 + 一般文字。
     * 設計說明：split("\n", -1) 確保每個 AttributedString 不含 \n，
     * 保證 Screen.render() 行數計算與 terminal 實際渲染行數一致，
     * 游標永遠停在 ❯ prompt 行。
     */
    public synchronized void appendAiReply(String text) {
        String[] parts = text.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            var sb = new AttributedStringBuilder();
            if (i == 0) sb.styled(BRAND_STYLE, "⏺ ");
            else sb.append("  ");
            sb.append(parts[i]);
            var line = sb.toAttributedString();
            lines.add(line);
            incrementalCacheUpdate(line);
        }
        lines.add(AttributedString.EMPTY);
        incrementalCacheUpdate(AttributedString.EMPTY);
        scrollToBottomIfAutoFollow();
    }
```

- [ ] **Step 2: Run the `appendAiReply` tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.tui.view.ContentViewTest.appendAiReply*" -i 2>&1 | tail -20`

Expected: All 3 `appendAiReply_*` tests PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/tui/view/ContentView.java
git commit -m "fix: appendAiReply — split on \\n to keep cursor on ❯ line"
```

---

### Task 3: Fix `appendError()` — split multi-line error

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/tui/view/ContentView.java:93-101`

- [ ] **Step 1: Replace `appendError` method**

Replace lines 93-101 (after Task 2 line numbers will shift — find the method by signature) with:

```java
    /**
     * 附加錯誤訊息：⚠ 前綴 + 紅色文字。
     * 設計說明：同 appendAiReply — split("\n") 保證單行不變量。
     */
    public synchronized void appendError(String text) {
        String[] parts = text.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            var sb = new AttributedStringBuilder();
            sb.styled(AttributedStyle.DEFAULT.foreground(196),
                    (i == 0 ? "⚠ " : "  ") + parts[i]);
            var line = sb.toAttributedString();
            lines.add(line);
            incrementalCacheUpdate(line);
        }
        lines.add(AttributedString.EMPTY);
        incrementalCacheUpdate(AttributedString.EMPTY);
        scrollToBottomIfAutoFollow();
    }
```

- [ ] **Step 2: Run the `appendError` tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.tui.view.ContentViewTest.appendError*" -i 2>&1 | tail -20`

Expected: Both `appendError_*` tests PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/tui/view/ContentView.java
git commit -m "fix: appendError — split on \\n to keep cursor on ❯ line"
```

---

### Task 4: Fix `appendLine()` — defensive `\n` check

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/tui/view/ContentView.java:106-110`

- [ ] **Step 1: Replace `appendLine` method**

Replace the current `appendLine` method with:

```java
    /**
     * 附加一行原始 AttributedString（用於 streaming 等特殊場景）。
     * 設計說明：防禦性檢查 — 若含 \n 則 split（會丟失 style，
     * 可接受因為目前 caller 都不傳含 \n 的 AttributedString）。
     */
    public synchronized void appendLine(AttributedString line) {
        String raw = line.toString();
        if (raw.contains("\n")) {
            for (String part : raw.split("\n", -1)) {
                var as = new AttributedString(part);
                lines.add(as);
                incrementalCacheUpdate(as);
            }
        } else {
            lines.add(line);
            incrementalCacheUpdate(line);
        }
        scrollToBottomIfAutoFollow();
    }
```

- [ ] **Step 2: Run the `appendLine` tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.tui.view.ContentViewTest.appendLine*" -i 2>&1 | tail -20`

Expected: Both `appendLine_*` tests PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/tui/view/ContentView.java
git commit -m "fix: appendLine — defensive \\n split to keep cursor on ❯ line"
```

---

### Task 5: Fix `removeLastLine()` — sync wrappedCache

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/tui/view/ContentView.java:115-119`

- [ ] **Step 1: Replace `removeLastLine` method**

Replace the current `removeLastLine` method with:

```java
    /**
     * 移除最後一行（用於移除 "thinking..." 等暫時狀態行）。
     * 設計說明：同步清理 wrappedCache — 一個 logical line 可能佔多個
     * wrappedCache entry（寬行 wrap 時 continuation 的 wrapped=true），
     * 需要反向移除所有 continuation 再移除 head。
     */
    public synchronized void removeLastLine() {
        if (!lines.isEmpty()) {
            lines.removeLast();
            while (!wrappedCache.isEmpty() && wrappedCache.getLast().wrapped()) {
                wrappedCache.removeLast();
            }
            if (!wrappedCache.isEmpty()) {
                wrappedCache.removeLast();
            }
        }
    }
```

- [ ] **Step 2: Run the `removeLastLine` tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.tui.view.ContentViewTest.removeLastLine*" -i 2>&1 | tail -20`

Expected: Both `removeLastLine_*` tests PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/tui/view/ContentView.java
git commit -m "fix: removeLastLine — sync wrappedCache for correct text selection"
```

---

### Task 6: Run full test suite and verify

**Files:** None (verification only)

- [ ] **Step 1: Run all ContentViewTest tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.tui.view.ContentViewTest" -i 2>&1 | tail -30`

Expected: All 10 tests PASS.

- [ ] **Step 2: Run full project test suite**

Run: `./gradlew test 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL. No regressions.

- [ ] **Step 3: Build the application**

Run: `./gradlew build 2>&1 | tail -10`

Expected: BUILD SUCCESSFUL.
