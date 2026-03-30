# F2-e: Git Worktree 隔離模式

> Date: 2026-03-30
> Status: Draft
> Phase: 1（基礎設施）
> Parent: [PRD](2026-03-27-grimo-orchestration-platform-prd.md)
> Depends: F2-d (WorkspaceProvisioner)
> Enables: F4 (Sub-Agent parallel dispatch)

## 問題

目前 Grimo 派遣 agent 時直接在使用者的工作目錄（CWD）執行。問題：

1. **Agent 改壞檔案無法回滾** — 沒有隔離，agent 的修改直接影響工作目錄
2. **多 agent 並行衝突** — F4 Sub-Agent dispatch 需要多個 agent 同時工作，共用 CWD 必然衝突
3. **沒有 review 機會** — 修改直接生效，使用者無法先看 diff 再決定是否接受

業界方案（Google Scion、Claude Code subagent、Codex parallel）都採用 **git worktree per agent** — 每個 agent 在獨立的 worktree 工作，完成後使用者決定是否 merge。

> 參考：[Google Scion](https://github.com/GoogleCloudPlatform/scion) — 每個 agent 一個 container + git worktree + 獨立 credentials

## 目標

1. 每次 agent 派遣都在獨立 git worktree 中執行（統一模式，不分單一/多 agent）
2. Agent 完成後顯示 diff 摘要，使用者決定 merge 或 discard
3. 非 git 目錄自動 fallback 到 CWD（現有行為）
4. 為 F4 Sub-Agent parallel dispatch 打好基礎

## 設計

### 統一 Worktree 派遣流程

```
使用者輸入
  │
  ├─ 1. 偵測是否為 git repo
  │     ├─ Yes → git worktree add /tmp/grimo-<uuid> -b grimo/<uuid-short>
  │     └─ No  → fallback CWD + WARN log
  │
  ├─ 2. provision skills → worktree/.agents/skills/
  │
  ├─ 3. AgentClient.workingDirectory(worktreePath)
  │     Agent 在 worktree 中獨立工作
  │
  ├─ 4. Agent 完成 → TUI 顯示結果 + diff 摘要
  │     Branch: grimo/abc123
  │     Files changed: 3 (+42 -15)
  │
  ├─ 5. git worktree remove（清理 worktree 目錄）
  │     分支保留，不刪除
  │
  └─ 6. 使用者可在之後 merge 分支
```

### WorkspaceProvisioner API 變更

現有 `provision()` / `cleanup()` 統一為 worktree-based：

```java
/**
 * Agent 派遣的工作區資訊。
 *
 * @param workDir agent 的工作目錄（worktree path 或 fallback CWD）
 * @param branchName worktree 分支名稱（fallback 時為 null）
 * @param provisionedSkills 已配置的 skill 名稱
 * @param isWorktree true=git worktree 模式, false=fallback CWD
 */
public record WorktreeInfo(
    Path workDir,
    String branchName,
    String baseSha,             // worktree 建立時的 HEAD SHA（用於 diff 比較）
    List<String> provisionedSkills,
    boolean isWorktree
) {}

/**
 * 準備 agent 工作區：建立 git worktree + provision skills。
 * 非 git 目錄或 worktree 建立失敗時 fallback 到 CWD + symlink。
 * 永遠不拋例外 — 失敗時回傳 isWorktree=false 的 WorktreeInfo。
 */
public WorktreeInfo provision(Path projectDir, String taskId, List<SkillDefinition> skills);

/**
 * 清理工作區：移除 worktree 目錄 + skill symlinks。
 * 保留分支（讓使用者可以 merge）。
 */
public void cleanup(WorktreeInfo info);
```

### Git Worktree 機制

**建立：**
```java
// 1. 記錄 base commit SHA（用於之後的 diff 比較）
String baseSha = exec("git", "rev-parse", "HEAD");  // e.g. "abc123def456"

// 2. 建立 temp 目錄（Java NIO，跨平台）
Path worktreeDir = Files.createTempDirectory("grimo-worktree-");

// 3. 建立 git worktree
exec("git", "worktree", "add", worktreeDir.toString(), "-b", "grimo/" + taskId);
```

- 使用 `Files.createTempDirectory`（跨平台，macOS `/private/tmp`、Linux `/tmp`）
- 分支名稱：`grimo/<taskId>`（8 字元 UUID short，避免衝突）
- 基於當前 HEAD，agent 看到最新的程式碼
- `baseSha` 存入 `WorktreeInfo` 供 diff 比較用

**清理：**
```bash
# 檢查 worktree 是否有未提交變更
git -C /tmp/grimo-<uuid> status --porcelain

# 有未提交變更 → 自動 commit（保留 agent 的工作）
git -C /tmp/grimo-<uuid> add -A
git -C /tmp/grimo-<uuid> commit -m "grimo: auto-commit uncommitted agent changes"

# 移除 worktree 目錄（分支保留）
git worktree remove /tmp/grimo-<uuid> --force
```

- 清理前先檢查未提交變更，有的話自動 commit + WARN log，避免丟失 agent 的工作
- `--force`：移除 worktree 目錄
- 分支不刪除：使用者可以 `git merge grimo/<uuid-short>` 或 `git branch -D grimo/<uuid-short>`

**Diff 摘要（agent 完成後）：**
```bash
# 比較 worktree 分支與建立時的 base commit（不依賴分支名稱）
git diff --stat <baseSha>...grimo/<uuid-short>
git log --oneline <baseSha>..grimo/<uuid-short>
```

`baseSha` 在建立 worktree 前用 `git rev-parse HEAD` 取得，存入 `WorktreeInfo.baseSha`。

### 派遣流程變更（GrimoTuiRunner）

現在：
```java
var projectDir = Path.of(System.getProperty("user.dir"));
var provisionedSkills = workspaceProvisioner.provision(projectDir, skillRegistry.listAll());
try {
    // display skills...
    var client = AgentClient.builder(model)...build();
    var response = client.goal(text).workingDirectory(projectDir).run();
    // handle response...
} finally {
    workspaceProvisioner.cleanup(projectDir, provisionedSkills);
}
```

之後：
```java
var projectDir = Path.of(System.getProperty("user.dir"));
var taskId = java.util.UUID.randomUUID().toString().substring(0, 8);
var worktree = workspaceProvisioner.provision(projectDir, taskId, skillRegistry.listAll());
try {
    // display skills + worktree info...
    for (var skillName : worktree.provisionedSkills()) { /* TUI display */ }
    if (worktree.isWorktree()) { /* display worktree branch */ }

    var client = AgentClient.builder(model)...build();
    var response = client.goal(text).workingDirectory(worktree.workDir()).run();

    // agent 完成後顯示 diff 摘要（只有 worktree 模式 + 有 commits 時）
    if (worktree.isWorktree()) {
        displayDiffSummary(projectDir, worktree);
    }
    // handle response...
} finally {
    workspaceProvisioner.cleanup(worktree);
}
```

### Fallback：非 git 目錄

如果 `projectDir` 不在 git repo 中（`git rev-parse --git-dir` 失敗）：
- Fallback 到現有行為：CWD + symlink skills
- `WorktreeInfo.isWorktree()` 回傳 `false`
- `log.warn("Not a git repository, falling back to CWD mode")`

### TUI 顯示

**派遣時（worktree 建立後）：**
```
● Skill(code-review)
  └ Successfully loaded skill
● Worktree(grimo/abc123)
  └ Isolated workspace created
⏳ thinking...
```

**Agent 完成後（有檔案變更時）：**
```
⏺ Agent 完成 (12s)
  Branch: grimo/abc123
  Files changed: 3 (+42 -15)
  Commits: 2
  → git merge grimo/abc123
```

**Agent 完成後（無檔案變更時）：**
```
⏺ Hi! I'm ready to help with the Grimo CLI project.
```

只有 agent 產生了 commits 時才顯示 branch/diff 資訊。純對話不顯示。

### Log

| 時機 | Level | 訊息 |
|------|-------|------|
| Worktree 建立 | INFO | `Created worktree: /tmp/grimo-xxx on branch grimo/abc123` |
| Fallback CWD | WARN | `Not a git repository, falling back to CWD mode` |
| Worktree 失敗 | WARN | `Failed to create worktree: {error}, falling back to CWD` |
| Agent 完成 | INFO | `Agent completed on branch grimo/abc123: 3 files changed, 2 commits` |
| 無變更 | DEBUG | `No commits on worktree branch, skipping diff summary` |
| 未提交變更 | WARN | `Auto-committed uncommitted changes on branch grimo/xxx` |
| Worktree 清理 | DEBUG | `Removed worktree: /tmp/grimo-xxx` |

## F4 並行 — 自然支援

由於每次派遣都建獨立 worktree，F4 的 parallel dispatch 不需要額外處理隔離：

**併發安全說明：**
- 目前 `GrimoTuiRunner` 的 `agentRunning` 守衛限制同時只有一個 agent — 此 spec 不改變這個限制
- F4 實作時需移除 `agentRunning` 守衛，改為 `List<WorktreeInfo>` 追蹤多個 active agents
- `WorkspaceProvisioner.provision()` 本身是 thread-safe 的：每次建立獨立的 worktree 目錄（UUID 保證不重複），無共享狀態
- F4 需要的併發變更屬於 F4 scope，不在此 spec 範圍內

```
/multi-review src/auth/
  │
  ├─ Sub-Agent A: git worktree add /tmp/grimo-aaa -b grimo/logic-review-aaa
  │   └── .agents/skills/ provisioned
  │
  └─ Sub-Agent B: git worktree add /tmp/grimo-bbb -b grimo/arch-review-bbb
      └── .agents/skills/ provisioned

  各自獨立工作，不衝突
  完成後各有自己的分支
```

## 影響範圍

| 動作 | 檔案 | 變更 |
|------|------|------|
| Modify | `WorkspaceProvisioner.java` | 重寫：provision() 建立 worktree + skills，cleanup() 移除 worktree |
| Create | `WorkspaceProvisionerTest.java` | 新增 worktree 測試（需要 git repo fixture） |
| Modify | `GrimoTuiRunner.java` | 使用新 provision() API，agent 完成後顯示 diff 摘要 |
| Modify | `docs/glossary.md` | 新增 Worktree 術語 |

## 驗證方式

1. 在 git repo 中啟動 Grimo
2. 輸入 "fix the TODO in README.md"
3. 確認 TUI 顯示 `● Worktree(grimo/xxx) └ Isolated workspace created`
4. Agent 完成後顯示 diff 摘要（branch、files changed、commits）
5. 確認 worktree 目錄已清理（`/tmp/grimo-xxx` 不存在）
6. 確認分支存在（`git branch | grep grimo/`）
7. 在非 git 目錄啟動 → fallback CWD，WARN log

## 參考

- [Google Scion — Git Worktree per Agent](https://github.com/GoogleCloudPlatform/scion) — 每個 agent 一個 container + worktree
- [Git Worktree Documentation](https://git-scm.com/docs/git-worktree)
- [How Git Worktrees Changed My AI Agent Workflow](https://nx.dev/blog/git-worktrees-ai-agents)
- [F4: Sub-Agent Dispatch Spec](2026-03-27-f4-subagent-dispatch.md) — parallel dispatch 需要 worktree 隔離
