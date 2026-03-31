# Session Isolation Phase B: Dev Mode 實作

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使用者可透過 `/dev` 指令或 skill `metadata.grimo.execution: isolated` 自動進入 Dev Mode — worktree 隔離 + agent 全開權限，完成後 diff summary + merge options。

**Architecture:** 新增 `DevModeRunner` 封裝 Dev Mode 生命週期（worktree 建立 → agent dispatch（DEV mode options）→ diff summary → merge/discard）。`/dev` 指令和 skill 自動觸發都呼叫 `DevModeRunner`。利用現有 `WorkspaceProvisioner` + `GitHelper` + `TierOptionsFactory.ExecutionMode.DEV`。

**Tech Stack:** Java 25, Spring AI Community Agent Client 0.10.0-SNAPSHOT, `WorkspaceProvisioner`（已有），`GitHelper`（已有），`TierOptionsFactory.ExecutionMode`（Phase A 已做）

**Spec:** `docs/superpowers/specs/2026-03-31-session-isolation-design.md` Phase B

**已完成的基礎：**
- Phase A：`TierOptionsFactory.ExecutionMode.PLAN/DEV`（已實作）
- Phase C：per-message worktree 已移除，主對話直接用 CWD
- `WorkspaceProvisioner.provision()` / `cleanup()` 已有完整 worktree lifecycle
- `GitHelper.getDiffStat()` / `getCommitCount()` 已有 diff 能力
- `SkillDefinition.grimoExecution()` 已能讀取 `metadata.grimo.execution`

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/io/github/samzhu/grimo/agent/DevModeRunner.java` | Dev Mode 生命週期封裝 |
| Create | `src/main/java/io/github/samzhu/grimo/agent/DevCommands.java` | `/dev` 斜線指令 |
| Create | `src/main/java/io/github/samzhu/grimo/shared/event/DevModeEnteredEvent.java` | Dev Mode 進入事件 |
| Create | `src/main/java/io/github/samzhu/grimo/shared/event/DevModeCompletedEvent.java` | Dev Mode 完成事件 |
| Modify | `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java` | 監聽 DevMode events 更新 TUI |

---

### Task 1: Dev Mode Events

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/DevModeEnteredEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/DevModeCompletedEvent.java`

- [ ] **Step 1: Create DevModeEnteredEvent**

```java
package io.github.samzhu.grimo.shared.event;

/**
 * Dev Mode 進入時發布。
 * TUI 收到後顯示 worktree 資訊。
 */
public record DevModeEnteredEvent(String branchName, String workDir) {}
```

- [ ] **Step 2: Create DevModeCompletedEvent**

```java
package io.github.samzhu.grimo.shared.event;

/**
 * Dev Mode 完成時發布。
 * TUI 收到後顯示 diff summary + merge options。
 *
 * @param branchName worktree 分支名稱
 * @param commitCount baseSha 到分支的 commit 數量
 * @param diffStat diff 統計文字（git diff --stat 格式）
 * @param durationMs 執行時間（毫秒）
 * @param hasChanges agent 是否實際修改了檔案
 * @param result agent 回覆文字
 */
public record DevModeCompletedEvent(
    String branchName,
    int commitCount,
    String diffStat,
    long durationMs,
    boolean hasChanges,
    String result
) {}
```

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/event/DevModeEnteredEvent.java \
       src/main/java/io/github/samzhu/grimo/shared/event/DevModeCompletedEvent.java
git commit -m "feat(isolation): add DevModeEnteredEvent + DevModeCompletedEvent"
```

---

### Task 2: DevModeRunner — Dev Mode 生命週期

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/DevModeRunner.java`

- [ ] **Step 1: Create DevModeRunner**

```java
package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.tier.TierOptionsFactory;
import io.github.samzhu.grimo.agent.tier.TierRouter;
import io.github.samzhu.grimo.agent.tier.TierKeywordDetector;
import io.github.samzhu.grimo.mcp.McpCatalogBuilder;
import io.github.samzhu.grimo.shared.event.DevModeEnteredEvent;
import io.github.samzhu.grimo.shared.event.DevModeCompletedEvent;
import io.github.samzhu.grimo.shared.sandbox.GitHelper;
import io.github.samzhu.grimo.shared.sandbox.WorkspaceProvisioner;
import io.github.samzhu.grimo.shared.sandbox.WorktreeInfo;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agents.client.AgentClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dev Mode 生命週期管理。
 *
 * 設計說明：
 * - 建立 worktree → provision skills → dispatch agent（DEV mode, 全開）→ diff → 發布完成事件
 * - 由 /dev 指令或 skill metadata.grimo.execution=isolated 觸發
 * - 完成後由 TUI event listener 顯示 diff summary + merge options
 * - 遵循 CLAUDE.md：Command → Event → TUI 解耦
 *
 * @see TierOptionsFactory.ExecutionMode#DEV
 * @see <a href="https://code.claude.com/docs/en/common-workflows">Claude Code Worktrees</a>
 */
@Component
public class DevModeRunner {

    private static final Logger log = LoggerFactory.getLogger(DevModeRunner.class);

    private final WorkspaceProvisioner workspaceProvisioner;
    private final GitHelper gitHelper;
    private final AgentModelRegistry agentModelRegistry;
    private final TierRouter tierRouter;
    private final TierKeywordDetector tierKeywordDetector;
    private final TierOptionsFactory tierOptionsFactory;
    private final McpCatalogBuilder mcpCatalogBuilder;
    private final SkillRegistry skillRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final AtomicReference<io.github.samzhu.grimo.agent.tier.Tier> sessionTier;

    public DevModeRunner(WorkspaceProvisioner workspaceProvisioner,
                         GitHelper gitHelper,
                         AgentModelRegistry agentModelRegistry,
                         TierRouter tierRouter,
                         TierKeywordDetector tierKeywordDetector,
                         TierOptionsFactory tierOptionsFactory,
                         McpCatalogBuilder mcpCatalogBuilder,
                         SkillRegistry skillRegistry,
                         ApplicationEventPublisher eventPublisher,
                         AtomicReference<io.github.samzhu.grimo.agent.tier.Tier> sessionTier) {
        this.workspaceProvisioner = workspaceProvisioner;
        this.gitHelper = gitHelper;
        this.agentModelRegistry = agentModelRegistry;
        this.tierRouter = tierRouter;
        this.tierKeywordDetector = tierKeywordDetector;
        this.tierOptionsFactory = tierOptionsFactory;
        this.mcpCatalogBuilder = mcpCatalogBuilder;
        this.skillRegistry = skillRegistry;
        this.eventPublisher = eventPublisher;
        this.sessionTier = sessionTier;
    }

    /**
     * 執行 Dev Mode：worktree → agent dispatch → diff → event。
     * 在 virtual thread 中呼叫（非阻塞主線程）。
     *
     * @param goal 使用者的目標描述
     * @param projectDir 專案根目錄
     */
    public void run(String goal, Path projectDir) {
        long startTime = System.currentTimeMillis();
        String taskId = UUID.randomUUID().toString().substring(0, 8);

        // 1. Tier routing
        var keywordTier = tierKeywordDetector.detect(goal).orElse(null);
        var tierCtx = TierRouter.Context.builder()
                .keywordTier(keywordTier)
                .sessionTier(sessionTier.get())
                .build();
        var tierSelection = tierRouter.resolve(tierCtx);

        var agentModel = agentModelRegistry.get(tierSelection.agentId());
        if (agentModel == null) {
            log.error("Agent not found for dev mode: {}", tierSelection.agentId());
            return;
        }

        // 2. Dev Mode options（全開）
        var devOptions = tierOptionsFactory.build(
                tierSelection.agentId(), tierSelection.model(),
                TierOptionsFactory.ExecutionMode.DEV);

        // 3. 建 worktree
        var worktree = workspaceProvisioner.provision(
                projectDir, taskId, skillRegistry.listAll());

        // 發布進入事件
        eventPublisher.publishEvent(new DevModeEnteredEvent(
                worktree.branchName(), worktree.workDir().toString()));

        log.info("Dev Mode entered: branch={}, goal={}",
                worktree.branchName(), goal.length() > 80 ? goal.substring(0, 80) + "..." : goal);

        try {
            // 4. Agent dispatch（全開權限）
            var client = AgentClient.builder(agentModel)
                    .mcpServerCatalog(mcpCatalogBuilder.getCatalog())
                    .defaultMcpServers(mcpCatalogBuilder.getServerNames())
                    .defaultWorkingDirectory(worktree.workDir())
                    .build();
            var response = client.run(goal, devOptions);

            long duration = System.currentTimeMillis() - startTime;

            // 5. Diff summary
            int commitCount = 0;
            String diffStat = "";
            boolean hasChanges = false;

            if (worktree.isWorktree() && worktree.baseSha() != null) {
                // cleanup 會 auto-commit 未提交的變更
                // 先 cleanup 再取 diff（cleanup 保留分支）
                workspaceProvisioner.cleanup(worktree, projectDir);

                try {
                    commitCount = gitHelper.getCommitCount(
                            projectDir, worktree.baseSha(), worktree.branchName());
                    if (commitCount > 0) {
                        diffStat = gitHelper.getDiffStat(
                                projectDir, worktree.baseSha(), worktree.branchName());
                        hasChanges = true;
                    }
                } catch (Exception e) {
                    log.debug("Branch already cleaned up (no changes): {}", e.getMessage());
                }
            }

            // 6. 發布完成事件
            eventPublisher.publishEvent(new DevModeCompletedEvent(
                    worktree.branchName(), commitCount, diffStat, duration,
                    hasChanges,
                    response.isSuccessful() ? response.getResult() : response.getResult()));

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Dev Mode failed: duration={}ms, error={}", duration, e.getMessage(), e);

            // 清理 worktree
            try {
                workspaceProvisioner.cleanup(worktree, projectDir);
            } catch (Exception ce) {
                log.warn("Dev Mode cleanup failed: {}", ce.getMessage());
            }

            eventPublisher.publishEvent(new DevModeCompletedEvent(
                    worktree.branchName(), 0, "", duration, false,
                    "Dev Mode error: " + e.getMessage()));
        }
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/DevModeRunner.java
git commit -m "feat(isolation): add DevModeRunner — worktree + full-access agent dispatch lifecycle"
```

---

### Task 3: `/dev` 指令

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/DevCommands.java`

- [ ] **Step 1: Create DevCommands**

```java
package io.github.samzhu.grimo.agent;

import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

/**
 * /dev 指令：進入 Dev Mode（worktree + 全開權限）。
 *
 * 設計說明：
 * - /dev <goal> → 建 worktree + dispatch agent（DEV mode）
 * - 不帶 goal → 顯示用法
 * - 實際執行委託給 DevModeRunner（在 virtual thread 中）
 * - 完成後 TUI 收到 DevModeCompletedEvent 顯示 diff + merge options
 */
@Component
public class DevCommands {

    private final DevModeRunner devModeRunner;

    public DevCommands(DevModeRunner devModeRunner) {
        this.devModeRunner = devModeRunner;
    }

    @Command(name = "dev", description = "Enter Dev Mode (worktree + full access)")
    public String dev(String input) {
        if (input == null || input.isBlank()) {
            return "Usage: /dev <goal>\nExample: /dev fix the auth bug in LoginService";
        }

        var projectDir = java.nio.file.Path.of(System.getProperty("user.dir"));

        // 在 virtual thread 中執行（非阻塞）
        Thread.startVirtualThread(() -> devModeRunner.run(input.trim(), projectDir));

        return "⚡ Entering Dev Mode...";
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/DevCommands.java
git commit -m "feat(isolation): add /dev command — enters Dev Mode with worktree isolation"
```

---

### Task 4: GrimoTuiRunner 監聽 Dev Mode 事件

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`

- [ ] **Step 1: Add event listener methods**

Read `GrimoTuiRunner.java` and find the existing `@EventListener` methods (around the `on(AgentSwitchedEvent)` method). Add these new listeners:

```java
import io.github.samzhu.grimo.shared.event.DevModeEnteredEvent;
import io.github.samzhu.grimo.shared.event.DevModeCompletedEvent;
```

```java
@EventListener
void on(DevModeEnteredEvent event) {
    if (contentView == null) return;

    var wtLine = new org.jline.utils.AttributedStringBuilder();
    wtLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(2), "⚡ ");
    wtLine.append("Dev Mode (" + event.branchName() + ")");
    contentView.appendLine(wtLine.toAttributedString());

    var statusLine = new org.jline.utils.AttributedStringBuilder();
    statusLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
            "  └ Worktree created, agent working with full access...");
    contentView.appendLine(statusLine.toAttributedString());

    if (eventLoop != null) eventLoop.setDirty();
}

@EventListener
void on(DevModeCompletedEvent event) {
    if (contentView == null) return;

    float seconds = event.durationMs() / 1000f;

    if (event.hasChanges()) {
        // 有變更 → 顯示 diff summary + merge 提示
        var headerLine = new org.jline.utils.AttributedStringBuilder();
        headerLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(2),
                "⏺ Dev Mode completed (" + String.format("%.0fs", seconds) + ")");
        contentView.appendLine(headerLine.toAttributedString());

        var branchLine = new org.jline.utils.AttributedStringBuilder();
        branchLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                "  Branch: " + event.branchName());
        contentView.appendLine(branchLine.toAttributedString());

        if (!event.diffStat().isBlank()) {
            for (String line : event.diffStat().split("\n")) {
                var diffLine = new org.jline.utils.AttributedStringBuilder();
                diffLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                        "  " + line.trim());
                contentView.appendLine(diffLine.toAttributedString());
            }
        }

        var commitLine = new org.jline.utils.AttributedStringBuilder();
        commitLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                "  Commits: " + event.commitCount());
        contentView.appendLine(commitLine.toAttributedString());

        var mergeLine = new org.jline.utils.AttributedStringBuilder();
        mergeLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(67),
                "  → git merge " + event.branchName());
        contentView.appendLine(mergeLine.toAttributedString());
    } else {
        // 無變更
        var line = new org.jline.utils.AttributedStringBuilder();
        line.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                "⏺ Dev Mode completed (" + String.format("%.0fs", seconds) + ") — no file changes");
        contentView.appendLine(line.toAttributedString());
    }

    // 顯示 agent 回覆
    if (event.result() != null && !event.result().isBlank()) {
        contentView.appendAiReply(event.result());
    }

    contentView.appendLine(org.jline.utils.AttributedString.EMPTY);
    if (eventLoop != null) eventLoop.setDirty();
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "feat(isolation): GrimoTuiRunner listens DevMode events for TUI display"
```

---

### Task 5: Glossary + spec update

**Files:**
- Modify: `docs/glossary.md`
- Modify: `docs/superpowers/specs/2026-03-31-session-isolation-design.md`

- [ ] **Step 1: Update glossary**

Add:

```markdown
| **DevModeRunner** | Dev Mode Runner | Dev Mode 生命週期管理。建 worktree → dispatch agent（DEV 全開）→ diff summary → 發布事件。由 /dev 指令或 skill 自動觸發。 |
| **DevModeEnteredEvent** | Dev Mode Entered Event | Dev Mode 進入時發布。TUI 顯示 worktree 資訊。 |
| **DevModeCompletedEvent** | Dev Mode Completed Event | Dev Mode 完成時發布。TUI 顯示 diff summary + merge 提示。 |
```

- [ ] **Step 2: Mark spec Phase B as Done**

In `2026-03-31-session-isolation-design.md`, change:
```
Phase B：Dev Mode 實作
```
To:
```
Phase B：Dev Mode 實作 ✅ Done
```

Also mark Phase C as Done (per-message worktree already removed).

- [ ] **Step 3: Commit**

```bash
git add docs/glossary.md docs/superpowers/specs/2026-03-31-session-isolation-design.md
git commit -m "docs: update glossary + mark Session Isolation Phase B/C as Done"
```

---

### Task 6: Manual Verification

- [ ] **Step 1: Build and run**

```bash
./run.sh
```

- [ ] **Step 2: Test Plan Mode (main session)**

```
hi
```

Expected: Agent replies normally, NO worktree created.

```
幫我在 README 加一行 hello
```

Expected: Agent should be restricted by `disallowedTools` (Plan Mode).

- [ ] **Step 3: Test Dev Mode**

```
/dev fix the TODO in README.md
```

Expected TUI output:
```
⚡ Dev Mode (grimo/dev-abc123)
  └ Worktree created, agent working with full access...

⏺ Dev Mode completed (12s)
  Branch: grimo/dev-abc123
  1 file changed (+1 -0)
  Commits: 1
  → git merge grimo/dev-abc123
```

- [ ] **Step 4: Verify worktree branch preserved**

```bash
git branch | grep grimo/dev
```

Expected: Branch exists for merging.
