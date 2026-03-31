# SDK Upgrade: agent-client 0.10.0-SNAPSHOT → 0.11.0

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade Spring AI Community agent-client from 0.10.0-SNAPSHOT to 0.11.0 GA release, eliminating snapshot dependency.

**Architecture:** 只改 `build.gradle.kts` artifact ID + version。Java package 不變、API 相容。同時更新 bug report doc 記錄 workaround。

**Tech Stack:** Gradle Kotlin DSL, Spring AI Community agent-client 0.11.0

**Known issue:** MCP Options Bug 在 0.11.0 未修復（`docs/superpowers/specs/2026-03-31-sdk-mcp-options-bug.md`）

---

## File Structure

| Action | File | Change |
|--------|------|--------|
| Modify | `build.gradle.kts:42-46` | artifact ID + version |
| Modify | `docs/superpowers/specs/2026-03-31-sdk-mcp-options-bug.md` | 記錄 0.11.0 仍未修復 + workaround |

---

### Task 1: Upgrade build.gradle.kts

**Files:**
- Modify: `build.gradle.kts:42-46`

- [ ] **Step 1: Replace dependency coordinates**

In `build.gradle.kts`, replace lines 42-46:

```kotlin
// 舊
implementation("org.springaicommunity.agents:spring-ai-agent-model:0.10.0-SNAPSHOT")
implementation("org.springaicommunity.agents:spring-ai-agent-client:0.10.0-SNAPSHOT")
implementation("org.springaicommunity.agents:spring-ai-claude-agent:0.10.0-SNAPSHOT")
implementation("org.springaicommunity.agents:spring-ai-gemini:0.10.0-SNAPSHOT")
implementation("org.springaicommunity.agents:spring-ai-codex-agent:0.10.0-SNAPSHOT")
```

With:

```kotlin
// Spring AI Community — agent-client 0.11.0 GA (Maven Central)
// Known issue: MCP Options Bug (see docs/superpowers/specs/2026-03-31-sdk-mcp-options-bug.md)
implementation("org.springaicommunity.agents:agent-model:0.11.0")
implementation("org.springaicommunity.agents:agent-client-core:0.11.0")
implementation("org.springaicommunity.agents:agent-claude:0.11.0")
implementation("org.springaicommunity.agents:agent-gemini:0.11.0")
implementation("org.springaicommunity.agents:agent-codex:0.11.0")
```

Also update the testImplementation sandbox dependency (line 61):

```kotlin
// 舊
testImplementation("org.springaicommunity:agent-sandbox-docker:0.9.1-SNAPSHOT")
// 新 — 確認 0.11.0 是否有對應版本，如果沒有就保留
testImplementation("org.springaicommunity:agent-sandbox-docker:0.9.1-SNAPSHOT")
```

- [ ] **Step 2: Check if snapshot repositories can be removed**

Lines 19-27 have two snapshot repos. The agent-client is now GA, but other deps may still need snapshots (Spring AI 2.0.0-M3 is a milestone, not snapshot). Check:

```bash
./gradlew dependencies --configuration runtimeClasspath 2>&1 | grep -i SNAPSHOT | head -10
```

If no SNAPSHOT deps remain, remove the snapshot repositories. If some remain, keep them.

- [ ] **Step 3: Resolve dependencies**

Run: `./gradlew dependencies --configuration runtimeClasspath 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (dependencies resolve from Maven Central)

- [ ] **Step 4: Compile**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.*" --tests "io.github.samzhu.grimo.shared.sandbox.*" --tests "io.github.samzhu.grimo.shared.tui.*" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL — all tests pass

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts
git commit -m "chore: upgrade agent-client 0.10.0-SNAPSHOT → 0.11.0 GA (artifact IDs renamed)"
```

---

### Task 2: Update bug report doc

**Files:**
- Modify: `docs/superpowers/specs/2026-03-31-sdk-mcp-options-bug.md`

- [ ] **Step 1: Add version tracking section**

At the end of the doc, add:

```markdown
## 版本追蹤

| 版本 | 狀態 | 備註 |
|------|------|------|
| 0.10.0-SNAPSHOT | Bug 存在 | 初次發現 |
| 0.11.0 GA | **Bug 仍存在** | main 與 v0.11.0 tag 一致，無修復 commit |

## Grimo Workaround

Commit `8cc722b`: 主對話改回 DEV mode（不使用 per-request disallowedTools）。
隔離由 `/dev` 指令的 git worktree 提供，不依賴 SDK 工具限制。

待 upstream 修復後，可恢復 per-request options 傳遞。
追蹤 issue: TBD（待開 GitHub issue）
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-03-31-sdk-mcp-options-bug.md
git commit -m "docs: update SDK bug report — confirmed unfixed in 0.11.0, document workaround"
```
