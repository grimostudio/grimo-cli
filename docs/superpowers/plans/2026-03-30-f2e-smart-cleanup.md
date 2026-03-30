# F2-e Smart Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pure conversations don't leave behind meaningless git branches — only agent runs that actually modify files preserve their branch.

**Architecture:** Cleanup order changes: remove Grimo symlinks first, then check for real agent changes. If no changes remain, delete both worktree and branch. GitHelper gains a `deleteBranch` method.

**Tech Stack:** Java 25, ProcessBuilder (`git branch -D`), JUnit 5 + AssertJ

**Spec:** `docs/superpowers/specs/2026-03-30-f2e-smart-cleanup.md`

---

## File Structure

| Action | File | Change |
|--------|------|--------|
| Modify | `src/main/java/io/github/samzhu/grimo/shared/sandbox/GitHelper.java` | Add `deleteBranch()` |
| Modify | `src/test/java/io/github/samzhu/grimo/shared/sandbox/GitHelperTest.java` | Add `deleteBranch` test |
| Modify | `src/main/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisioner.java` | Rewrite `cleanup()` |
| Modify | `src/test/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisionerTest.java` | Add smart cleanup tests |

---

### Task 1: Add GitHelper.deleteBranch + test

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/sandbox/GitHelper.java`
- Modify: `src/test/java/io/github/samzhu/grimo/shared/sandbox/GitHelperTest.java`

- [ ] **Step 1: Write the failing test**

Add to `GitHelperTest.java`:

```java
@Test
void deleteBranchShouldRemoveBranch() throws Exception {
    Path repo = createGitRepo();
    var helper = new GitHelper();
    Path worktreeDir = tempDir.resolve("worktree-del");
    helper.createWorktree(repo, worktreeDir, "grimo/to-delete");
    helper.removeWorktree(repo, worktreeDir);

    // branch should exist before delete
    String before = exec(repo, "git", "branch", "--list", "grimo/to-delete");
    assertThat(before).contains("grimo/to-delete");

    helper.deleteBranch(repo, "grimo/to-delete");

    // branch should be gone after delete
    String after = exec(repo, "git", "branch", "--list", "grimo/to-delete");
    assertThat(after.trim()).isEmpty();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.sandbox.GitHelperTest.deleteBranchShouldRemoveBranch" 2>&1 | tail -10`
Expected: FAIL — method `deleteBranch` not found

- [ ] **Step 3: Implement deleteBranch**

Add to `GitHelper.java` after the `removeWorktree` method:

```java
/**
 * 刪除本地分支（force delete）。
 * 用於純對話無檔案變更時，清理不需要的 worktree 分支。
 */
public void deleteBranch(Path repoDir, String branchName) {
    exec(repoDir, "git", "branch", "-D", branchName);
    log.debug("Deleted branch: {}", branchName);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.sandbox.GitHelperTest.deleteBranchShouldRemoveBranch" 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/sandbox/GitHelper.java \
       src/test/java/io/github/samzhu/grimo/shared/sandbox/GitHelperTest.java
git commit -m "feat(f2e): add GitHelper.deleteBranch for smart cleanup"
```

---

### Task 2: Rewrite WorkspaceProvisioner.cleanup + tests

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisioner.java:92-109`
- Modify: `src/test/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisionerTest.java`

- [ ] **Step 1: Write the failing tests**

Add two tests to `WorkspaceProvisionerTest.java`:

```java
@Test
void cleanupShouldDeleteBranchWhenNoAgentChanges() throws Exception {
    Path repo = createGitRepo();
    lastRepoDir = repo;
    setupSkillSource("code-review");

    var provisioner = new WorkspaceProvisioner(skillsSourceDir, new GitHelper());
    var info = provisioner.provision(repo, "task-pure", List.of(testSkill("code-review")));
    createdWorktrees.add(info);

    // agent 沒改任何檔案，只有 Grimo 的 symlinks
    provisioner.cleanup(info, repo);

    // 分支應該被刪除（純對話不留痕跡）
    String branches = exec(repo, "git", "branch", "--list", "grimo/task-pure");
    assertThat(branches.trim()).isEmpty();
}

@Test
void cleanupShouldKeepBranchWhenAgentModifiedFiles() throws Exception {
    Path repo = createGitRepo();
    lastRepoDir = repo;

    var provisioner = new WorkspaceProvisioner(skillsSourceDir, new GitHelper());
    var info = provisioner.provision(repo, "task-code", List.of());
    createdWorktrees.add(info);

    // agent 修改了檔案
    Files.writeString(info.workDir().resolve("agent-output.txt"), "real work");

    provisioner.cleanup(info, repo);

    // 分支應該保留（agent 有實際變更）
    String branches = exec(repo, "git", "branch", "--list", "grimo/task-code");
    assertThat(branches).contains("grimo/task-code");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.sandbox.WorkspaceProvisionerTest.cleanupShouldDeleteBranchWhenNoAgentChanges" 2>&1 | tail -10`
Expected: FAIL — branch still exists after cleanup

- [ ] **Step 3: Rewrite cleanup method**

Replace `WorkspaceProvisioner.java` lines 92-109 (the `cleanup` method) with:

```java
    /**
     * 清理工作區：移除 symlinks → 判斷是否有真正的 agent 變更 → 決定保留或刪除分支。
     *
     * 設計說明：
     * - 先移除 Grimo provisioned 的 .agents/skills/ symlinks
     * - 再檢查是否有 agent 真正修改的檔案（uncommitted changes）
     * - 有變更 → auto-commit + 保留分支（使用者可 merge）
     * - 無變更 → 刪除分支 + worktree，不留痕跡（純對話場景）
     *
     * @param info provision() 回傳的 WorktreeInfo
     * @param projectDir 使用者的專案目錄（worktree 模式需要在主 repo 執行 git 指令）
     */
    public void cleanup(WorktreeInfo info, Path projectDir) {
        if (info.isWorktree()) {
            try {
                // 1. 先移除 Grimo 建立的 symlinks，避免它們被當作 agent 的變更
                cleanupSymlinks(info.workDir(), info.provisionedSkills());

                // 2. 檢查是否有 agent 真正修改的檔案
                if (gitHelper.hasUncommittedChanges(info.workDir())) {
                    // agent 有實際變更 → auto-commit + 保留分支
                    gitHelper.autoCommit(info.workDir());
                    gitHelper.removeWorktree(projectDir, info.workDir());
                    log.info("Agent modified files on branch {}", info.branchName());
                } else {
                    // 純對話，無變更 → 刪除 worktree + 分支，不留痕跡
                    gitHelper.removeWorktree(projectDir, info.workDir());
                    gitHelper.deleteBranch(projectDir, info.branchName());
                    log.debug("No agent changes, cleaned up branch {}", info.branchName());
                }
            } catch (Exception e) {
                log.warn("Failed to cleanup worktree: {}", e.getMessage());
            }
        } else {
            // Fallback 模式：只移除 symlinks
            cleanupSymlinks(info.workDir(), info.provisionedSkills());
        }
    }
```

- [ ] **Step 4: Run all WorkspaceProvisioner tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.sandbox.WorkspaceProvisionerTest" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL — all 11 tests pass

- [ ] **Step 5: Run full sandbox test suite**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.sandbox.*" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisioner.java \
       src/test/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisionerTest.java
git commit -m "feat(f2e): smart cleanup — pure conversations don't leave branches"
```
