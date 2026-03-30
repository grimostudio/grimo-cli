# F2-e 補充：Smart Cleanup — 純對話不留分支

> Date: 2026-03-30
> Status: Draft
> Parent: [F2-e Worktree Isolation](2026-03-30-f2e-worktree-isolation.md)

## 問題

F2-e worktree isolation 在每次 agent 派遣後，cleanup 時若偵測到 uncommitted changes 會自動 commit 並保留分支。但 Grimo 自己 provision 的 `.agents/skills/` symlinks 也算 uncommitted changes，導致純對話（如 "hi"）也留下無意義的 auto-commit 和分支。

**實際行為：**
```
使用者輸入 "hi"
  → 建立 worktree + symlink skills
  → agent 回答 "Hi! I'm ready to help..."（沒改任何檔案）
  → cleanup: hasUncommittedChanges = true（因為 symlinks）
  → auto-commit symlinks → 保留分支 grimo/xxx
  → 使用者看到一個無意義的分支
```

**期望行為：**
```
使用者輸入 "hi"
  → 建立 worktree + symlink skills
  → agent 回答（沒改檔案）
  → cleanup: 先移除 symlinks → hasUncommittedChanges = false
  → 刪除分支 + worktree，不留痕跡
```

## 設計

### Cleanup 流程變更

現在：
```
cleanup(info, projectDir):
  if hasUncommittedChanges → autoCommit
  removeWorktree（保留分支）
```

之後：
```
cleanup(info, projectDir):
  1. cleanupSymlinks（移除 Grimo provisioned 的 .agents/skills/ symlinks）
  2. if hasUncommittedChanges:
       autoCommit
       removeWorktree（保留分支）
       log.info("Agent modified files on branch {}")
     else:
       removeWorktree
       deleteBranch（分支也刪）
       log.debug("No agent changes, cleaned up branch {}")
```

### 新增 GitHelper.deleteBranch()

```java
/**
 * 刪除本地分支。
 * 用於純對話無檔案變更時，清理不需要的 worktree 分支。
 */
public void deleteBranch(Path repoDir, String branchName) {
    exec(repoDir, "git", "branch", "-D", branchName);
    log.debug("Deleted branch: {}", branchName);
}
```

使用 `-D`（force delete）因為 worktree 分支未 merge 到任何分支。

## 影響範圍

| 動作 | 檔案 | 變更 |
|------|------|------|
| Modify | `WorkspaceProvisioner.java` | cleanup() 先刪 symlinks 再判斷，無變更時刪分支 |
| Modify | `GitHelper.java` | 新增 `deleteBranch(Path, String)` |
| Modify | `GitHelperTest.java` | 新增 deleteBranch 測試 |
| Modify | `WorkspaceProvisionerTest.java` | 新增「純對話不留分支」測試 |

GrimoTuiRunner 不需改 — `displayDiffSummary` 已有 `commitCount == 0` 判斷。

## 驗證方式

1. 啟動 Grimo，輸入 "hi"
2. Agent 回答後，執行 `git branch | grep grimo/` → 不應有新分支
3. 輸入 "fix the TODO in README.md"
4. Agent 修改檔案後，執行 `git branch | grep grimo/` → 應保留分支
5. TUI 顯示 diff 摘要（branch、files changed、commits）
