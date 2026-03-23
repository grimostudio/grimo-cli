# Status Line + Prompt Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persistent JLine Status bar at the terminal bottom showing agent/model/workspace/resources, simplify the prompt to `❯`, and coordinate the slash menu with the status bar via suspend/restore.

**Architecture:** New `StatusLineRenderer` wraps JLine's `org.jline.utils.Status` singleton. `GrimoPromptProvider` is simplified to a single `❯` symbol. `SlashMenuRenderer` gains suspend/restore coordination and replaces `\033[s`/`\033[u` with pure relative cursor movement (`\033[nA`/`\033[nB`).

**Tech Stack:** Java 25, Spring Shell 4.0.1, JLine 3 (`org.jline.utils.Status`, `AttributedString`, `AttributedStringBuilder`, `AttributedStyle`)

**Spec:** `docs/superpowers/specs/2026-03-23-grimo-statusline-prompt-redesign.md`

---

### Task 1: StatusLineRenderer — buildStatusLine() pure function + test

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/StatusLineRenderer.java`
- Create: `src/test/java/io/github/samzhu/grimo/StatusLineRendererTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.github.samzhu.grimo;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StatusLineRendererTest {

    @Test
    void buildStatusLineShouldContainAllSegments() {
        var renderer = new StatusLineRenderer(null); // null terminal = no-op mode
        AttributedString line = renderer.buildStatusLine(
            "anthropic", "sonnet4.5", "~/grimo-workspace", 1, 3, 2, 1);
        String plain = line.toString(); // plain text without ANSI
        assertThat(plain).contains("anthropic");
        assertThat(plain).contains("sonnet4.5");
        assertThat(plain).contains("~/grimo-workspace");
        assertThat(plain).contains("1 agent");
        assertThat(plain).contains("3 skill");
        assertThat(plain).contains("2 mcp");
        assertThat(plain).contains("1 task");
    }

    @Test
    void buildStatusLineShouldContainSeparators() {
        var renderer = new StatusLineRenderer(null);
        AttributedString line = renderer.buildStatusLine(
            "anthropic", "sonnet4.5", "~/test", 0, 0, 0, 0);
        String plain = line.toString();
        assertThat(plain).contains("│");
        assertThat(plain).contains("·");
    }

    @Test
    void updateShouldBeNoOpWhenTerminalIsNull() {
        var renderer = new StatusLineRenderer(null);
        // Should not throw
        renderer.update("anthropic", "sonnet4.5", "~/test", 0, 0, 0, 0);
        renderer.suspend();
        renderer.restore();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.samzhu.grimo.StatusLineRendererTest" --info 2>&1 | tail -20`
Expected: FAIL — class `StatusLineRenderer` does not exist

- [ ] **Step 3: Write StatusLineRenderer implementation**

```java
package io.github.samzhu.grimo;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

import java.util.List;

/**
 * 封裝 JLine Status API，在終端底部顯示持久狀態列。
 *
 * 設計說明：
 * - 使用 Status.getStatus(terminal) 取得或建立 per-terminal 單例（內部存在 terminal attributes 中）
 * - null terminal 或不支援 Status 的終端（dumb/null）下所有操作為 no-op
 * - buildStatusLine() 是純函數，方便單元測試
 * - update() 加 synchronized 避免多執行緒同時更新造成終端輸出錯亂
 * - suspend()/restore() 供 SlashMenuRenderer 在選單開啟/關閉時暫停/恢復狀態列
 *
 * 格式：provider · model │ workspace │ N agent · N skill · N mcp · N task
 *
 * @see <a href="https://github.com/jline/jline3/blob/master/terminal/src/main/java/org/jline/utils/Status.java">JLine Status.java</a>
 */
public class StatusLineRenderer {

    private static final AttributedStyle CYAN = AttributedStyle.DEFAULT.foreground(37);
    private static final AttributedStyle GRAY = AttributedStyle.DEFAULT.foreground(245);
    private static final AttributedStyle WHITE_BOLD = AttributedStyle.BOLD.foreground(AttributedStyle.WHITE);

    private final Status status;

    /** 儲存最近一次的參數，供 restore() 後重新繪製 */
    private volatile String lastProvider, lastModel, lastWorkspace;
    private volatile int lastAgentCount, lastSkillCount, lastMcpCount, lastTaskCount;

    public StatusLineRenderer(Terminal terminal) {
        this.status = (terminal != null) ? Status.getStatus(terminal) : null;
    }

    /**
     * 純函數：組裝狀態列 AttributedString。
     * 格式：provider · model │ workspace │ N agent · N skill · N mcp · N task
     */
    public AttributedString buildStatusLine(String provider, String model,
                                             String workspacePath,
                                             int agentCount, int skillCount,
                                             int mcpCount, int taskCount) {
        var sb = new AttributedStringBuilder();
        sb.append(" ");
        sb.styled(CYAN, provider);
        sb.styled(GRAY, " · ");
        sb.styled(CYAN, model);
        sb.styled(GRAY, " │ ");
        sb.styled(GRAY, workspacePath);
        sb.styled(GRAY, " │ ");
        sb.styled(WHITE_BOLD, String.valueOf(agentCount));
        sb.styled(GRAY, " agent · ");
        sb.styled(WHITE_BOLD, String.valueOf(skillCount));
        sb.styled(GRAY, " skill · ");
        sb.styled(WHITE_BOLD, String.valueOf(mcpCount));
        sb.styled(GRAY, " mcp · ");
        sb.styled(WHITE_BOLD, String.valueOf(taskCount));
        sb.styled(GRAY, " task");
        return sb.toAttributedString();
    }

    /**
     * 更新終端底部狀態列。synchronized 避免多執行緒同時呼叫。
     * terminal 為 null 或不支援 Status 時為 no-op。
     */
    public synchronized void update(String provider, String model, String workspacePath,
                                     int agentCount, int skillCount, int mcpCount, int taskCount) {
        this.lastProvider = provider;
        this.lastModel = model;
        this.lastWorkspace = workspacePath;
        this.lastAgentCount = agentCount;
        this.lastSkillCount = skillCount;
        this.lastMcpCount = mcpCount;
        this.lastTaskCount = taskCount;
        if (status != null) {
            status.update(List.of(buildStatusLine(provider, model, workspacePath,
                    agentCount, skillCount, mcpCount, taskCount)));
        }
    }

    /** 暫停狀態列顯示（選單開啟時呼叫）。 */
    public synchronized void suspend() {
        if (status != null) {
            status.suspend();
        }
    }

    /** 恢復狀態列顯示（選單關閉時呼叫），使用最近一次的參數重新繪製。 */
    public synchronized void restore() {
        if (status != null) {
            status.restore();
            if (lastProvider != null) {
                status.update(List.of(buildStatusLine(lastProvider, lastModel, lastWorkspace,
                        lastAgentCount, lastSkillCount, lastMcpCount, lastTaskCount)));
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.samzhu.grimo.StatusLineRendererTest" --info 2>&1 | tail -20`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/StatusLineRenderer.java src/test/java/io/github/samzhu/grimo/StatusLineRendererTest.java
git commit -m "feat: add StatusLineRenderer with JLine Status API"
```

---

### Task 2: Simplify GrimoPromptProvider to `❯`

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoPromptProvider.java`
- Modify: `src/test/java/io/github/samzhu/grimo/GrimoPromptProviderTest.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java` (bean definition)

- [ ] **Step 1: Update the tests**

Replace `GrimoPromptProviderTest.java` with:

```java
package io.github.samzhu.grimo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GrimoPromptProviderTest {

    @Test
    void promptShouldBeMinimalArrow() {
        var provider = new GrimoPromptProvider();
        var prompt = provider.getPrompt();
        assertThat(prompt.toString()).isEqualTo("❯ ");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.samzhu.grimo.GrimoPromptProviderTest" --info 2>&1 | tail -20`
Expected: FAIL — constructor mismatch or assertion fail

- [ ] **Step 3: Simplify GrimoPromptProvider**

Replace `GrimoPromptProvider.java` with:

```java
package io.github.samzhu.grimo;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.springframework.shell.jline.PromptProvider;

/**
 * 極簡 Shell 提示符：只顯示 ❯ 箭頭符號。
 * 所有狀態資訊（agent、model、workspace）由 StatusLineRenderer 在終端底部顯示。
 *
 * @see StatusLineRenderer
 */
public class GrimoPromptProvider implements PromptProvider {

    private static final AttributedStyle CYAN = AttributedStyle.DEFAULT.foreground(37);

    @Override
    public AttributedString getPrompt() {
        return new AttributedString("❯ ", CYAN);
    }
}
```

- [ ] **Step 4: Update GrimoStartupRunner bean definition**

In `GrimoStartupRunner.java`, change the `grimoPromptProvider` bean from:

```java
@Bean
GrimoPromptProvider grimoPromptProvider(AgentProviderRegistry registry, GrimoConfig grimoConfig) {
    return new GrimoPromptProvider(registry, grimoConfig);
}
```

to:

```java
@Bean
GrimoPromptProvider grimoPromptProvider() {
    return new GrimoPromptProvider();
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.GrimoPromptProviderTest" --info 2>&1 | tail -20`
Expected: 1 test PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoPromptProvider.java src/test/java/io/github/samzhu/grimo/GrimoPromptProviderTest.java src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java
git commit -m "feat: simplify prompt to ❯, move status info to status line"
```

---

### Task 3: Integrate StatusLineRenderer into GrimoStartupRunner

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`

- [ ] **Step 1: Add StatusLineRenderer bean**

In `GrimoStartupRunner.java`, add bean definition after `bannerRenderer()`:

```java
@Bean
StatusLineRenderer statusLineRenderer(Terminal terminal) {
    return new StatusLineRenderer(terminal);
}
```

- [ ] **Step 2: Add StatusLineRenderer to grimoStartup parameters and call update()**

Add `StatusLineRenderer statusLineRenderer` to the `grimoStartup` method parameters.

After the banner print block (after `terminal.writer().flush()`), add:

```java
// 8. 初始化底部狀態列
statusLineRenderer.update(agentId, model, workspacePath,
        (int) agentCount,
        skillRegistry.listAll().size(),
        mcpClientRegistry.listAll().size(),
        taskSchedulerService.getScheduledTaskIds().size());
```

- [ ] **Step 3: Run full build to verify integration**

Run: `./gradlew build 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java
git commit -m "feat: initialize status line after startup banner"
```

---

### Task 4: Fix SlashMenuRenderer cursor management + StatusLineRenderer coordination

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/SlashMenuRenderer.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java` (widget wiring)

- [ ] **Step 1: Refactor SlashMenuRenderer constructor to accept StatusLineRenderer**

Change constructor from `SlashMenuRenderer(Terminal terminal)` to:

```java
private final Terminal terminal;
private final PrintWriter writer;
private final NonBlockingReader reader;
private final StatusLineRenderer statusLine; // nullable

public SlashMenuRenderer(Terminal terminal, StatusLineRenderer statusLine) {
    this.terminal = terminal;
    this.writer = terminal.writer();
    this.reader = terminal.reader();
    this.statusLine = statusLine;
}
```

- [ ] **Step 2: Add suspend/restore calls in show()**

At the start of `show()`, before the first `render()` call:

```java
if (statusLine != null) {
    statusLine.suspend();
}
```

In `cleanup()`, after clearing drawn lines:

```java
private void cleanup() {
    if (drawnLines > 0) {
        clearDrawnLines();
    }
    writer.flush();
    if (statusLine != null) {
        statusLine.restore();
    }
}
```

- [ ] **Step 3: Replace `\033[s`/`\033[u` with relative cursor movement in render()**

Replace the `render()` method to use only `\033[nA` (cursor up) instead of save/restore:

```java
/**
 * 清除舊畫面 → 繪製新選單 → 回到 prompt 行。
 * 完全使用相對行移動（\033[nA / \033[nB），不使用 save/restore（\033[s / \033[u），
 * 避免單一 slot 衝突。用 drawnLines 計數器追蹤已繪製行數。
 */
private void render(List<MenuItem> filtered, int selectedIndex, int scrollOffset, String filter) {
    // 1. 清除之前畫的選單行
    if (drawnLines > 0) {
        clearDrawnLines();
    }

    // 2. 繪製選單（在 prompt 下方）
    writer.println(); // 換行到選單區域
    drawnLines = 0;

    int visible = Math.min(MAX_VISIBLE, filtered.size() - scrollOffset);
    if (filtered.isEmpty()) {
        writer.print("  " + DIM + "(no matching commands)" + RESET + CLEAR_LINE);
        writer.println();
        drawnLines = 2; // blank line + "no matching" line
    } else {
        for (int i = 0; i < visible; i++) {
            int idx = scrollOffset + i;
            var item = filtered.get(idx);
            boolean selected = (idx == selectedIndex);

            if (selected) {
                writer.print(CYAN + "❯ " + RESET);
            } else {
                writer.print("  ");
            }

            String cmdDisplay = "/" + item.command();
            if (selected) {
                writer.print(WHITE_BOLD + cmdDisplay + RESET);
            } else {
                writer.print(CYAN + cmdDisplay + RESET);
            }

            int padding = Math.max(1, 28 - cmdDisplay.length());
            writer.print(" ".repeat(padding));
            writer.print(GRAY + truncate(item.description(), 50) + RESET);
            writer.print(CLEAR_LINE);
            writer.println();
        }
        drawnLines = visible + 1; // +1 for the blank line (println before menu)
    }

    // 清除選單下方殘留
    writer.print(CLEAR_LINE);

    // 3. 用相對行移動回到 prompt 行（不使用 \033[s/\033[u）
    if (drawnLines > 0) {
        writer.printf("\033[%dA", drawnLines);
    }
    // 回到行首，然後移到 filter 文字後面（prompt "❯ " 寬度 2 + "/" 寬度 1 + filter 長度）
    writer.print("\r");
    int cursorCol = 2 + 1 + filter.length(); // "❯ " + "/" + filter
    if (cursorCol > 0) {
        writer.printf("\033[%dC", cursorCol);
    }
    writer.flush();
}
```

- [ ] **Step 4: Replace `\033[s`/`\033[u` in clearDrawnLines()**

```java
/**
 * 清除已繪製的選單行。使用相對行移動，不使用 save/restore。
 * drawnLines 已包含 blank line，所以清除 drawnLines 行即可。
 */
private void clearDrawnLines() {
    // 從 prompt 行往下移動到選單區域，逐行清除
    for (int i = 0; i < drawnLines; i++) {
        writer.println();
        writer.print(CLEAR_LINE);
    }
    // 回到 prompt 行
    if (drawnLines > 0) {
        writer.printf("\033[%dA", drawnLines);
    }
    drawnLines = 0;
}
```

- [ ] **Step 5: Update widget wiring in GrimoStartupRunner**

In `GrimoStartupRunner.java`, change the `SlashMenuRenderer` construction in the widget from:

```java
var menu = new SlashMenuRenderer(terminal);
```

to:

```java
var menu = new SlashMenuRenderer(terminal, statusLineRenderer);
```

This requires adding `statusLineRenderer` to the lambda capture. Since it's already a parameter of `grimoStartup`, it's available in the closure.

- [ ] **Step 6: Run full build**

Run: `./gradlew build 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/SlashMenuRenderer.java src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java
git commit -m "fix: use relative cursor movement in slash menu, add status line coordination"
```

---

### Task 5: Wire runtime status updates into command handlers

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java`
- Modify: `src/main/java/io/github/samzhu/grimo/skill/SkillCommands.java`
- Modify: `src/main/java/io/github/samzhu/grimo/mcp/McpCommands.java`
- Modify: `src/main/java/io/github/samzhu/grimo/task/TaskCommands.java`

> **Note:** This task wires `StatusLineRenderer.update()` into command handlers so the status line
> refreshes when agents/skills/MCP/tasks change at runtime. The exact injection points depend on
> command class signatures — inject `StatusLineRenderer` via constructor and call `update()` after
> state changes (e.g., after `agent use`, `skill add`, `mcp add`, `task create`).

- [ ] **Step 1: Inject StatusLineRenderer into AgentCommands**

Add `StatusLineRenderer` as a constructor parameter. After `agent use` or `agent model` commands complete, call:

```java
statusLineRenderer.update(newAgentId, newModel, workspacePath,
    agentCount, skillCount, mcpCount, taskCount);
```

The exact parameter values come from the existing registries already injected in each Commands class.

- [ ] **Step 2: Repeat for SkillCommands, McpCommands, TaskCommands**

Same pattern: inject `StatusLineRenderer`, call `update()` after state-changing operations.

- [ ] **Step 3: Run full build**

Run: `./gradlew build 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java src/main/java/io/github/samzhu/grimo/skill/SkillCommands.java src/main/java/io/github/samzhu/grimo/mcp/McpCommands.java src/main/java/io/github/samzhu/grimo/task/TaskCommands.java
git commit -m "feat: update status line on agent/skill/mcp/task changes"
```

---

### Task 6: Manual integration test

- [ ] **Step 1: Run the app**

```bash
./run.sh
```

- [ ] **Step 2: Verify status line appears at bottom**

Expected: After banner displays, a status line appears at the terminal bottom:
```
 claude-cli · unknown │ ~/grimo-workspace │ 1 agent · 0 skill · 0 mcp · 0 task
```

- [ ] **Step 3: Verify prompt is `❯`**

Expected: Prompt shows `❯ ` in cyan, no `[no agent] grimo:>`.

- [ ] **Step 4: Verify slash menu**

1. Type `/` — menu should appear below prompt, status line disappears
2. Type `ag` — menu filters to agent commands
3. Press Backspace — filter clears correctly, no double `/`
4. Press ↑↓ — selection moves
5. Press Enter — command is selected
6. Press Esc — menu closes, status line reappears

- [ ] **Step 5: Verify `/exit` works**

Type `/exit` or `exit` — app should exit cleanly.
