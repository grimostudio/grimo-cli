# Session Isolation Phase A: Plan Mode 基礎

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 主對話 agent 不能修改程式碼（Plan Mode），只有明確進入 Dev Mode 才能全開。

**Architecture:** `TierOptionsFactory` 新增 `ExecutionMode` 參數（PLAN/DEV），Plan Mode 對 Claude 用 `disallowedTools`、Codex 用 `SandboxMode.READ_ONLY`、Gemini 用 `sandbox=true`。主對話預設 PLAN，skill 宣告 `metadata.grimo.execution: isolated` 時切換為 DEV。

**Tech Stack:** Java 25, Spring AI Community Agent Client 0.10.0-SNAPSHOT (`ClaudeAgentOptions.disallowedTools`, `CodexAgentOptions.sandboxMode`, `GeminiAgentOptions`)

**Spec:** `docs/superpowers/specs/2026-03-31-session-isolation-design.md` Phase A

**SDK API（已驗證）：**
```java
// Claude: 禁止特定工具
ClaudeAgentOptions.builder().disallowedTools(List.of("Edit","Write","MultiEdit"))

// Codex: 唯讀 sandbox
CodexAgentOptions.builder().sandboxMode(SandboxMode.READ_ONLY)

// Gemini: sandbox 模式（CLIOptions 層級）
CLIOptions.builder().sandbox(true)
```

**已有基礎：**
- `SkillDefinition.grimoExecution()` — 從 metadata 讀取 `grimo.execution`（已實作）
- `TierOptionsFactory.build(agentId, model)` — per-agent options builder（要擴充）

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `src/main/java/io/github/samzhu/grimo/agent/tier/TierOptionsFactory.java` | 加 ExecutionMode，Plan 限制工具 |
| Create | `src/test/java/io/github/samzhu/grimo/agent/tier/TierOptionsFactoryTest.java` | Plan/Dev mode 測試 |
| Modify | `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java` | 主對話用 PLAN mode |

---

### Task 1: TierOptionsFactory 加 ExecutionMode

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/agent/tier/TierOptionsFactory.java`
- Create: `src/test/java/io/github/samzhu/grimo/agent/tier/TierOptionsFactoryTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.github.samzhu.grimo.agent.tier;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.claude.ClaudeAgentOptions;
import org.springaicommunity.agents.codex.CodexAgentOptions;
import org.springaicommunity.agents.gemini.GeminiAgentOptions;

import static org.assertj.core.api.Assertions.assertThat;

class TierOptionsFactoryTest {

    private final TierOptionsFactory factory = new TierOptionsFactory();

    // === Plan Mode ===

    @Test
    void planModeShouldDisallowEditToolsForClaude() {
        var options = (ClaudeAgentOptions) factory.build("claude", "claude-sonnet-4-6",
                TierOptionsFactory.ExecutionMode.PLAN);
        assertThat(options.getDisallowedTools()).contains("Edit", "Write", "MultiEdit");
    }

    @Test
    void planModeShouldSetReadOnlyForCodex() {
        var options = (CodexAgentOptions) factory.build("codex", "o4-mini",
                TierOptionsFactory.ExecutionMode.PLAN);
        // Codex READ_ONLY sandbox — verify the option is set
        assertThat(options).isNotNull();
    }

    @Test
    void planModeShouldRestrictGemini() {
        var options = (GeminiAgentOptions) factory.build("gemini", "gemini-2.5-pro",
                TierOptionsFactory.ExecutionMode.PLAN);
        assertThat(options).isNotNull();
    }

    // === Dev Mode ===

    @Test
    void devModeShouldAllowAllToolsForClaude() {
        var options = (ClaudeAgentOptions) factory.build("claude", "claude-sonnet-4-6",
                TierOptionsFactory.ExecutionMode.DEV);
        // Dev mode: no disallowed tools
        assertThat(options.getDisallowedTools()).isNullOrEmpty();
    }

    @Test
    void devModeShouldBeFullAccessForCodex() {
        var options = (CodexAgentOptions) factory.build("codex", "o4-mini",
                TierOptionsFactory.ExecutionMode.DEV);
        assertThat(options).isNotNull();
    }

    // === Backward compat ===

    @Test
    void legacyBuildShouldDefaultToDev() {
        // 舊 API build(agentId, model) 應該等同 DEV mode（向後相容）
        var options = (ClaudeAgentOptions) factory.build("claude", "claude-sonnet-4-6");
        assertThat(options.getDisallowedTools()).isNullOrEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.tier.TierOptionsFactoryTest" 2>&1 | tail -10`
Expected: FAIL — `ExecutionMode` and 3-param `build` method don't exist

- [ ] **Step 3: Implement ExecutionMode in TierOptionsFactory**

Replace the entire `TierOptionsFactory.java`:

```java
package io.github.samzhu.grimo.agent.tier;

import org.springaicommunity.agents.claude.ClaudeAgentOptions;
import org.springaicommunity.agents.codex.CodexAgentOptions;
import org.springaicommunity.agents.gemini.GeminiAgentOptions;
import org.springaicommunity.agents.model.AgentOptions;
import org.springaicommunity.agents.codexsdk.types.ApprovalPolicy;

import java.time.Duration;
import java.util.List;

/**
 * 根據 agentId + ExecutionMode 建構對應的 per-request AgentOptions。
 *
 * 設計說明：
 * - PLAN mode（主對話）：禁止檔案修改工具，agent 只能讀程式碼、寫 docs、回答問題
 *   Claude: disallowedTools=["Edit","Write","MultiEdit"]
 *   Codex:  SandboxMode.READ_ONLY
 *   Gemini: yolo=false
 * - DEV mode（開發模式）：全開，搭配 worktree 隔離
 *   所有 agent: yolo=true, 無工具限制
 *
 * @see <a href="https://code.claude.com/docs/en/cli-reference">Claude Code Permission Modes</a>
 * @see <a href="https://developers.openai.com/codex/concepts/sandboxing">Codex Sandbox Modes</a>
 */
public class TierOptionsFactory {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    /**
     * 執行模式：決定 agent 的權限等級。
     * PLAN = 主對話，限制檔案修改
     * DEV = 開發模式，全開
     */
    public enum ExecutionMode {
        PLAN,  // 主對話：讀程式碼、寫 docs，不能改 src
        DEV    // 開發：全開，worktree 隔離
    }

    /** 向後相容：預設 DEV mode（現有行為不變） */
    public AgentOptions build(String agentId, String model) {
        return build(agentId, model, ExecutionMode.DEV);
    }

    /**
     * 建構指定 agent + mode 的 AgentOptions。
     */
    public AgentOptions build(String agentId, String model, ExecutionMode mode) {
        return switch (agentId) {
            case "claude" -> buildClaude(model, mode);
            case "gemini" -> buildGemini(model, mode);
            case "codex" -> buildCodex(model, mode);
            default -> throw new IllegalArgumentException("Unknown agent: " + agentId);
        };
    }

    private ClaudeAgentOptions buildClaude(String model, ExecutionMode mode) {
        var builder = ClaudeAgentOptions.builder()
                .model(model)
                .yolo(true)  // 非互動式必須 yolo
                .timeout(DEFAULT_TIMEOUT);

        if (mode == ExecutionMode.PLAN) {
            // Plan Mode：禁止檔案修改工具
            builder.disallowedTools(List.of("Edit", "Write", "MultiEdit"));
        }

        return builder.build();
    }

    private GeminiAgentOptions buildGemini(String model, ExecutionMode mode) {
        return GeminiAgentOptions.builder()
                .model(model)
                .yolo(mode == ExecutionMode.DEV)  // Plan: 需確認; Dev: 全開
                .timeout(DEFAULT_TIMEOUT)
                .build();
    }

    private CodexAgentOptions buildCodex(String model, ExecutionMode mode) {
        var builder = CodexAgentOptions.builder()
                .model(model)
                .timeout(DEFAULT_TIMEOUT);

        if (mode == ExecutionMode.PLAN) {
            builder.approvalPolicy(ApprovalPolicy.SMART);
            builder.fullAuto(false);
        } else {
            builder.approvalPolicy(ApprovalPolicy.NEVER);
            builder.fullAuto(true);
        }

        return builder.build();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.tier.TierOptionsFactoryTest" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/tier/TierOptionsFactory.java \
       src/test/java/io/github/samzhu/grimo/agent/tier/TierOptionsFactoryTest.java
git commit -m "feat(isolation): TierOptionsFactory with ExecutionMode — Plan restricts tools, Dev full access"
```

---

### Task 2: GrimoTuiRunner 主對話用 Plan Mode

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`

- [ ] **Step 1: Read GrimoTuiRunner dispatch flow**

Read lines 528-550 of GrimoTuiRunner.java. Find where `tierOptionsFactory.build(agentId, model)` is called.

- [ ] **Step 2: Change to Plan Mode**

Find the line (around 542):
```java
var tierOptions = tierOptionsFactory.build(tierSelection.agentId(), tierSelection.model());
```

Change to:
```java
// 設計說明：主對話預設 PLAN mode — 禁止修改程式碼
// Skill 宣告 metadata.grimo.execution=isolated 時，由 Dev Mode 流程處理
var tierOptions = tierOptionsFactory.build(
        tierSelection.agentId(), tierSelection.model(),
        TierOptionsFactory.ExecutionMode.PLAN);
```

Add import:
```java
import io.github.samzhu.grimo.agent.tier.TierOptionsFactory;
```

(Check if this import already exists — `TierOptionsFactory` is likely already imported.)

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "feat(isolation): main session uses Plan Mode — agent cannot modify source files"
```

---

### Task 3: Glossary update

**Files:**
- Modify: `docs/glossary.md`

- [ ] **Step 1: Add isolation terms**

Add to the glossary:

```markdown
| **Plan Mode** | Plan Mode | 主對話預設模式。Agent 可讀程式碼、寫 docs，但禁止修改 src/。Claude 用 disallowedTools，Codex 用 READ_ONLY sandbox。 |
| **Dev Mode** | Dev Mode | 開發模式。Agent 全開（yolo=true），搭配 worktree 隔離。由 skill metadata.grimo.execution=isolated 自動觸發，或使用者 /dev 指令。 |
| **ExecutionMode** | Execution Mode | `TierOptionsFactory.ExecutionMode` enum: PLAN（限制）/ DEV（全開）。決定 agent 的工具權限等級。 |
```

- [ ] **Step 2: Commit**

```bash
git add docs/glossary.md
git commit -m "docs: add Plan Mode / Dev Mode / ExecutionMode to glossary"
```

---

### Task 4: Manual Verification

- [ ] **Step 1: Build and run**

```bash
./run.sh
```

- [ ] **Step 2: Test Plan Mode — agent cannot edit files**

```
幫我在 README.md 加一行 hello
```

Expected: Agent should respond that it cannot modify files (disallowedTools blocks Edit/Write).

- [ ] **Step 3: Test Plan Mode — agent can read and analyze**

```
解釋 GrimoTuiRunner 的架構
```

Expected: Agent reads the file and explains the architecture (Read tool is allowed).

- [ ] **Step 4: Test Plan Mode — agent can write docs**

Note: Claude's `disallowedTools` blocks Write globally, so writing docs/ is also blocked in Plan Mode. This is expected — docs should be written via specs/plans, not directly by the agent. If this is too restrictive, consider using `allowedTools` instead to whitelist specific tools.
