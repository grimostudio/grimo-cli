# F2-e: Git Worktree Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every agent dispatch runs in an isolated git worktree, preventing file conflicts and enabling post-execution diff review.

**Architecture:** WorkspaceProvisioner gains git worktree lifecycle management. Before agent dispatch, a temporary worktree is created on a new branch (`grimo/<taskId>`). Skills are symlinked into the worktree. After completion, the worktree is removed but the branch is preserved for user merge. Non-git directories fallback to current CWD behavior.

**Tech Stack:** Java 25, ProcessBuilder (git CLI), Spring AI Community Agent Client 0.10.0-SNAPSHOT (`AgentClient.Builder.defaultWorkingDirectory(Path)`), JUnit 5 + AssertJ + `@TempDir`

**SDK API Verification:**
- `AgentClient.Builder.defaultWorkingDirectory(Path)` — confirmed exists (javap verified)
- `AgentClient.AgentClientRequestSpec.workingDirectory(Path)` — confirmed (per-request override)
- Git worktree: no Java library, use ProcessBuilder (`git worktree add/remove`)
- ClaudeAgentModel: NO `sandbox()` method — worktree isolation is the only local isolation for Claude
- GeminiAgentModel / CodexAgentModel: accept `Sandbox` in constructor — Docker mode is separate concern (Phase B)

**References:**
- Spec: `docs/superpowers/specs/2026-03-30-f2e-worktree-isolation.md`
- PRD: `docs/superpowers/specs/2026-03-27-grimo-orchestration-platform-prd.md`
- Glossary: `docs/glossary.md`
- [Git Worktree Docs](https://git-scm.com/docs/git-worktree)
- [Google Scion — worktree per agent](https://github.com/GoogleCloudPlatform/scion)
- [Spring AI Community Agent Client](https://github.com/spring-ai-community/agent-client)

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/io/github/samzhu/grimo/shared/sandbox/WorktreeInfo.java` | Immutable record: worktree metadata |
| Create | `src/main/java/io/github/samzhu/grimo/shared/sandbox/GitHelper.java` | Git CLI wrapper: worktree CRUD, diff, status |
| Modify | `src/main/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisioner.java` | Worktree-based provision/cleanup lifecycle |
| Modify | `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java:86-94` | Update bean definitions: add GitHelper bean, update WorkspaceProvisioner constructor |
| Modify | `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java` | Use new WorktreeInfo API + diff summary display |
| Create | `src/test/java/io/github/samzhu/grimo/shared/sandbox/GitHelperTest.java` | Unit tests for git operations |
| Modify | `src/test/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisionerTest.java` | Worktree-based provisioning tests |
| Modify | `docs/glossary.md` | Add Worktree / WorktreeInfo terms |

**Spec deviation note:** Spec defines `cleanup(WorktreeInfo info)` with 1 parameter. Plan uses `cleanup(WorktreeInfo info, Path projectDir)` with 2 parameters because `git worktree remove` must execute from the main repo directory, not the worktree itself. The worktree's `workDir` cannot be used as cwd for `git worktree remove`.

**AgentClient workingDirectory precedence:** `AgentClient.Builder.defaultWorkingDirectory(Path)` sets the working directory at request time and is passed through to `AgentTaskRequest.workingDirectory`. This takes precedence over the `workingDirectory` baked into each `AgentModel` at construction time, because the `AgentClient` passes its per-request `workingDirectory` to `AgentModel.call(AgentTaskRequest)`. Verified via javap: `AgentTaskRequest(String goal, Path workingDirectory, AgentOptions options)`.

---

### Task 1: WorktreeInfo Record

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/sandbox/WorktreeInfo.java`

- [ ] **Step 1: Create WorktreeInfo record**

```java
package io.github.samzhu.grimo.shared.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * Agent 派遣的工作區資訊。
 *
 * 設計說明：
 * - worktree 模式：agent 在獨立 git worktree 工作，完成後使用者決定是否 merge
 * - fallback 模式：非 git 目錄，直接使用 CWD + symlink skills（現有行為）
 * - baseSha 用於 diff 比較，避免依賴分支名稱
 *
 * @param workDir agent 的工作目錄（worktree path 或 fallback CWD）
 * @param branchName worktree 分支名稱（fallback 時為 null）
 * @param baseSha worktree 建立時的 HEAD SHA（用於 diff 比較，fallback 時為 null）
 * @param provisionedSkills 已配置的 skill 名稱
 * @param isWorktree true=git worktree 模式, false=fallback CWD
 */
public record WorktreeInfo(
    Path workDir,
    String branchName,
    String baseSha,
    List<String> provisionedSkills,
    boolean isWorktree
) {}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/sandbox/WorktreeInfo.java
git commit -m "feat(f2e): add WorktreeInfo record for worktree isolation metadata"
```

---

### Task 2: GitHelper — Git Command Utilities

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/sandbox/GitHelper.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/sandbox/GitHelperTest.java`

- [ ] **Step 1: Write failing tests for GitHelper**

```java
package io.github.samzhu.grimo.shared.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitHelperTest {

    @TempDir Path tempDir;

    /** 建立一個真實的 git repo（含初始 commit）做測試用 */
    private Path createGitRepo() throws Exception {
        Path repo = tempDir.resolve("test-repo");
        Files.createDirectories(repo);
        exec(repo, "git", "init");
        exec(repo, "git", "config", "user.email", "test@test.com");
        exec(repo, "git", "config", "user.name", "Test");
        Files.writeString(repo.resolve("README.md"), "# Test");
        exec(repo, "git", "add", ".");
        exec(repo, "git", "commit", "-m", "initial");
        return repo;
    }

    private String exec(Path dir, String... cmd) throws Exception {
        var pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        var process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) throw new RuntimeException("Command failed: " + String.join(" ", cmd) + "\n" + output);
        return output.trim();
    }

    @Test
    void isGitRepoShouldReturnTrueForGitDirectory() throws Exception {
        Path repo = createGitRepo();
        var helper = new GitHelper();
        assertThat(helper.isGitRepo(repo)).isTrue();
    }

    @Test
    void isGitRepoShouldReturnTrueForSubdirectory() throws Exception {
        Path repo = createGitRepo();
        Path subDir = repo.resolve("src");
        Files.createDirectories(subDir);
        var helper = new GitHelper();
        assertThat(helper.isGitRepo(subDir)).isTrue();
    }

    @Test
    void isGitRepoShouldReturnFalseForNonGitDirectory() {
        var helper = new GitHelper();
        assertThat(helper.isGitRepo(tempDir)).isFalse();
    }

    @Test
    void getHeadShaShouldReturnCommitHash() throws Exception {
        Path repo = createGitRepo();
        var helper = new GitHelper();
        String sha = helper.getHeadSha(repo);
        assertThat(sha).matches("[0-9a-f]{40}");
    }

    @Test
    void createWorktreeShouldCreateDirectoryAndBranch() throws Exception {
        Path repo = createGitRepo();
        var helper = new GitHelper();
        Path worktreeDir = tempDir.resolve("worktree-1");
        String branch = "grimo/test-001";

        helper.createWorktree(repo, worktreeDir, branch);

        assertThat(Files.isDirectory(worktreeDir)).isTrue();
        assertThat(Files.exists(worktreeDir.resolve("README.md"))).isTrue();
        // 確認分支存在
        String branches = exec(repo, "git", "branch", "--list", branch);
        assertThat(branches).contains(branch);
    }

    @Test
    void removeWorktreeShouldDeleteDirectoryButKeepBranch() throws Exception {
        Path repo = createGitRepo();
        var helper = new GitHelper();
        Path worktreeDir = tempDir.resolve("worktree-2");
        String branch = "grimo/test-002";
        helper.createWorktree(repo, worktreeDir, branch);

        helper.removeWorktree(repo, worktreeDir);

        assertThat(Files.exists(worktreeDir)).isFalse();
        // 分支應該保留
        String branches = exec(repo, "git", "branch", "--list", branch);
        assertThat(branches).contains(branch);
    }

    @Test
    void hasUncommittedChangesShouldDetectModifiedFiles() throws Exception {
        Path repo = createGitRepo();
        var helper = new GitHelper();
        Path worktreeDir = tempDir.resolve("worktree-3");
        helper.createWorktree(repo, worktreeDir, "grimo/test-003");

        // 在 worktree 裡修改檔案
        Files.writeString(worktreeDir.resolve("README.md"), "# Modified");

        assertThat(helper.hasUncommittedChanges(worktreeDir)).isTrue();
    }

    @Test
    void hasUncommittedChangesShouldReturnFalseWhenClean() throws Exception {
        Path repo = createGitRepo();
        var helper = new GitHelper();
        Path worktreeDir = tempDir.resolve("worktree-4");
        helper.createWorktree(repo, worktreeDir, "grimo/test-004");

        assertThat(helper.hasUncommittedChanges(worktreeDir)).isFalse();
    }

    @Test
    void autoCommitShouldCommitAllChanges() throws Exception {
        Path repo = createGitRepo();
        var helper = new GitHelper();
        Path worktreeDir = tempDir.resolve("worktree-5");
        helper.createWorktree(repo, worktreeDir, "grimo/test-005");

        Files.writeString(worktreeDir.resolve("new-file.txt"), "hello");
        helper.autoCommit(worktreeDir);

        assertThat(helper.hasUncommittedChanges(worktreeDir)).isFalse();
    }

    @Test
    void getDiffStatShouldShowChangedFiles() throws Exception {
        Path repo = createGitRepo();
        var helper = new GitHelper();
        String baseSha = helper.getHeadSha(repo);
        Path worktreeDir = tempDir.resolve("worktree-6");
        helper.createWorktree(repo, worktreeDir, "grimo/test-006");

        // 在 worktree commit 一個變更
        Files.writeString(worktreeDir.resolve("new-file.txt"), "content");
        exec(worktreeDir, "git", "add", ".");
        exec(worktreeDir, "git", "commit", "-m", "add file");

        String diffStat = helper.getDiffStat(repo, baseSha, "grimo/test-006");
        assertThat(diffStat).contains("new-file.txt");
    }

    @Test
    void getCommitCountShouldReturnNumberOfNewCommits() throws Exception {
        Path repo = createGitRepo();
        var helper = new GitHelper();
        String baseSha = helper.getHeadSha(repo);
        Path worktreeDir = tempDir.resolve("worktree-7");
        helper.createWorktree(repo, worktreeDir, "grimo/test-007");

        // 兩個 commit
        Files.writeString(worktreeDir.resolve("a.txt"), "a");
        exec(worktreeDir, "git", "add", ".");
        exec(worktreeDir, "git", "commit", "-m", "add a");
        Files.writeString(worktreeDir.resolve("b.txt"), "b");
        exec(worktreeDir, "git", "add", ".");
        exec(worktreeDir, "git", "commit", "-m", "add b");

        int count = helper.getCommitCount(repo, baseSha, "grimo/test-007");
        assertThat(count).isEqualTo(2);
    }

    @Test
    void getCommitCountShouldReturnZeroWhenNoNewCommits() throws Exception {
        Path repo = createGitRepo();
        var helper = new GitHelper();
        String baseSha = helper.getHeadSha(repo);
        Path worktreeDir = tempDir.resolve("worktree-8");
        helper.createWorktree(repo, worktreeDir, "grimo/test-008");

        int count = helper.getCommitCount(repo, baseSha, "grimo/test-008");
        assertThat(count).isEqualTo(0);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.sandbox.GitHelperTest" 2>&1 | tail -10`
Expected: FAIL — `GitHelper` class not found

- [ ] **Step 3: Implement GitHelper**

```java
package io.github.samzhu.grimo.shared.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Git CLI 操作工具：封裝 worktree 生命週期管理。
 *
 * 設計說明：
 * - 使用 ProcessBuilder 執行 git 指令（沒有 Java git worktree library）
 * - 所有方法拋 RuntimeException 讓呼叫端決定 fallback 策略
 * - baseSha 機制：建立 worktree 前記錄 HEAD SHA，用於 diff 比較
 *   避免依賴分支名稱，即使主分支有新 commit 也能正確比較
 *
 * @see <a href="https://git-scm.com/docs/git-worktree">Git Worktree Documentation</a>
 */
public class GitHelper {

    private static final Logger log = LoggerFactory.getLogger(GitHelper.class);

    /**
     * 檢查目錄是否在 git repo 內。
     */
    public boolean isGitRepo(Path dir) {
        try {
            String result = exec(dir, "git", "rev-parse", "--is-inside-work-tree");
            return "true".equals(result.trim());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 取得當前 HEAD commit SHA（40 字元）。
     */
    public String getHeadSha(Path repoDir) {
        return exec(repoDir, "git", "rev-parse", "HEAD").trim();
    }

    /**
     * 建立 git worktree + 新分支。
     *
     * @param repoDir 主 repo 目錄
     * @param worktreeDir worktree 目標目錄（不能已存在）
     * @param branchName 新分支名稱（e.g. "grimo/abc12345"）
     */
    public void createWorktree(Path repoDir, Path worktreeDir, String branchName) {
        exec(repoDir, "git", "worktree", "add", worktreeDir.toString(), "-b", branchName);
        log.info("Created worktree: {} on branch {}", worktreeDir, branchName);
    }

    /**
     * 移除 worktree 目錄（保留分支）。
     */
    public void removeWorktree(Path repoDir, Path worktreeDir) {
        exec(repoDir, "git", "worktree", "remove", worktreeDir.toString(), "--force");
        log.debug("Removed worktree: {}", worktreeDir);
    }

    /**
     * 檢查 worktree 是否有未提交變更（modified + untracked）。
     */
    public boolean hasUncommittedChanges(Path worktreeDir) {
        String status = exec(worktreeDir, "git", "status", "--porcelain");
        return !status.trim().isEmpty();
    }

    /**
     * 自動 commit 所有變更（保留 agent 的工作）。
     * 清理前呼叫，避免 worktree remove 時丟失 agent 未 commit 的修改。
     */
    public void autoCommit(Path worktreeDir) {
        exec(worktreeDir, "git", "add", "-A");
        exec(worktreeDir, "git", "commit", "-m", "grimo: auto-commit uncommitted agent changes");
        log.warn("Auto-committed uncommitted changes on branch in {}", worktreeDir);
    }

    /**
     * 取得 baseSha 到分支的 diff 統計（--stat 格式）。
     * 回傳空字串表示無差異。
     */
    public String getDiffStat(Path repoDir, String baseSha, String branchName) {
        return exec(repoDir, "git", "diff", "--stat", baseSha + "..." + branchName).trim();
    }

    /**
     * 計算 baseSha 到分支的新增 commit 數量。
     */
    public int getCommitCount(Path repoDir, String baseSha, String branchName) {
        String output = exec(repoDir, "git", "rev-list", "--count", baseSha + ".." + branchName).trim();
        try {
            return Integer.parseInt(output);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 執行 git 指令並回傳 stdout。
     * 失敗時拋 RuntimeException。
     */
    private String exec(Path workDir, String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            var process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Git command failed (exit " + exitCode + "): "
                        + String.join(" ", command) + "\n" + output);
            }
            return output;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Git command execution error: " + String.join(" ", command), e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.sandbox.GitHelperTest" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL — all 9 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/sandbox/GitHelper.java \
       src/test/java/io/github/samzhu/grimo/shared/sandbox/GitHelperTest.java
git commit -m "feat(f2e): add GitHelper for git worktree operations with tests"
```

---

### Task 3: Refactor WorkspaceProvisioner for Worktree Mode

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisioner.java`
- Modify: `src/test/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisionerTest.java`

- [ ] **Step 1: Write failing tests for worktree-based provisioning**

Update `WorkspaceProvisionerTest.java` — add worktree tests while keeping fallback tests:

```java
package io.github.samzhu.grimo.shared.sandbox;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceProvisionerTest {

    @TempDir Path tempDir;
    @TempDir Path skillsSourceDir;

    /** 建立真實 git repo（含初始 commit）*/
    private Path createGitRepo() throws Exception {
        Path repo = tempDir.resolve("test-repo");
        Files.createDirectories(repo);
        exec(repo, "git", "init");
        exec(repo, "git", "config", "user.email", "test@test.com");
        exec(repo, "git", "config", "user.name", "Test");
        Files.writeString(repo.resolve("README.md"), "# Test");
        exec(repo, "git", "add", ".");
        exec(repo, "git", "commit", "-m", "initial");
        return repo;
    }

    private String exec(Path dir, String... cmd) throws Exception {
        var pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        var process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) throw new RuntimeException("Failed: " + String.join(" ", cmd) + "\n" + output);
        return output.trim();
    }

    // === Worktree Mode Tests ===

    @Test
    void provisionShouldCreateWorktreeInGitRepo() throws Exception {
        Path repo = createGitRepo();
        setupSkillSource("code-review");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir, new GitHelper());
        var info = provisioner.provision(repo, "task-001", List.of(testSkill("code-review")));

        assertThat(info.isWorktree()).isTrue();
        assertThat(info.branchName()).isEqualTo("grimo/task-001");
        assertThat(info.baseSha()).matches("[0-9a-f]{40}");
        assertThat(info.workDir()).isNotEqualTo(repo);
        assertThat(Files.isDirectory(info.workDir())).isTrue();
        assertThat(info.provisionedSkills()).containsExactly("code-review");
        // skill 被 symlink 到 worktree 的 .agents/skills/
        assertThat(Files.isSymbolicLink(info.workDir().resolve(".agents/skills/code-review"))).isTrue();
    }

    @Test
    void provisionShouldIncludeRepoFilesInWorktree() throws Exception {
        Path repo = createGitRepo();
        var provisioner = new WorkspaceProvisioner(skillsSourceDir, new GitHelper());
        var info = provisioner.provision(repo, "task-002", List.of());

        // worktree 應該包含 repo 的檔案
        assertThat(Files.exists(info.workDir().resolve("README.md"))).isTrue();
    }

    @Test
    void cleanupShouldRemoveWorktreeButKeepBranch() throws Exception {
        Path repo = createGitRepo();
        setupSkillSource("code-review");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir, new GitHelper());
        var info = provisioner.provision(repo, "task-003", List.of(testSkill("code-review")));
        Path worktreeDir = info.workDir();

        provisioner.cleanup(info, repo);

        assertThat(Files.exists(worktreeDir)).isFalse();
        // 分支應保留
        String branches = exec(repo, "git", "branch", "--list", "grimo/task-003");
        assertThat(branches).contains("grimo/task-003");
    }

    @Test
    void cleanupShouldAutoCommitUncommittedChanges() throws Exception {
        Path repo = createGitRepo();
        var provisioner = new WorkspaceProvisioner(skillsSourceDir, new GitHelper());
        var info = provisioner.provision(repo, "task-004", List.of());

        // agent 在 worktree 修改但沒 commit
        Files.writeString(info.workDir().resolve("agent-output.txt"), "result");

        provisioner.cleanup(info, repo);

        // 切到該分支確認有 commit
        String log = exec(repo, "git", "log", "--oneline", "grimo/task-004");
        assertThat(log).contains("grimo: auto-commit");
    }

    // === Fallback Mode Tests（非 git 目錄）===

    @Test
    void provisionShouldFallbackToCwdForNonGitDir() throws Exception {
        Path nonGitDir = tempDir.resolve("non-git");
        Files.createDirectories(nonGitDir);
        setupSkillSource("code-review");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir, new GitHelper());
        var info = provisioner.provision(nonGitDir, "task-005", List.of(testSkill("code-review")));

        assertThat(info.isWorktree()).isFalse();
        assertThat(info.workDir()).isEqualTo(nonGitDir);
        assertThat(info.branchName()).isNull();
        assertThat(info.baseSha()).isNull();
        assertThat(info.provisionedSkills()).containsExactly("code-review");
        // skill 直接 symlink 到 CWD 的 .agents/skills/
        assertThat(Files.isSymbolicLink(nonGitDir.resolve(".agents/skills/code-review"))).isTrue();
    }

    @Test
    void cleanupFallbackShouldRemoveSymlinksOnly() throws Exception {
        Path nonGitDir = tempDir.resolve("non-git-2");
        Files.createDirectories(nonGitDir);
        setupSkillSource("code-review");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir, new GitHelper());
        var info = provisioner.provision(nonGitDir, "task-006", List.of(testSkill("code-review")));
        provisioner.cleanup(info, nonGitDir);

        assertThat(Files.exists(nonGitDir.resolve(".agents/skills/code-review"))).isFalse();
        // CWD 目錄本身不該被刪除
        assertThat(Files.isDirectory(nonGitDir)).isTrue();
    }

    @Test
    void provisionShouldReturnEmptySkillsWhenNoneProvided() throws Exception {
        Path repo = createGitRepo();
        var provisioner = new WorkspaceProvisioner(skillsSourceDir, new GitHelper());
        var info = provisioner.provision(repo, "task-007", List.of());

        assertThat(info.isWorktree()).isTrue();
        assertThat(info.provisionedSkills()).isEmpty();
    }

    @Test
    void provisionShouldFallbackWhenWorktreeCreationFails() throws Exception {
        Path repo = createGitRepo();
        // 建立同名分支讓 worktree add 失敗
        exec(repo, "git", "branch", "grimo/conflict-task");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir, new GitHelper());
        var info = provisioner.provision(repo, "conflict-task", List.of());

        assertThat(info.isWorktree()).isFalse();
        assertThat(info.workDir()).isEqualTo(repo);
    }

    @Test
    void provisionShouldSkipConflictingUserSkillInWorktree() throws Exception {
        Path repo = createGitRepo();
        // 先在 repo 建立使用者自己的 skill
        Path userSkillDir = repo.resolve(".agents/skills/code-review");
        Files.createDirectories(userSkillDir);
        Files.writeString(userSkillDir.resolve("SKILL.md"), "user version");
        exec(repo, "git", "add", ".");
        exec(repo, "git", "commit", "-m", "add user skill");

        setupSkillSource("code-review");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir, new GitHelper());
        var info = provisioner.provision(repo, "task-008", List.of(testSkill("code-review")));

        // 使用者的 skill 應該優先（不被 Grimo 覆蓋）
        // worktree 裡應該有使用者的 skill（從 repo 繼承）
        assertThat(info.provisionedSkills()).isEmpty();
    }

    // === Helpers ===

    private void setupSkillSource(String name) throws Exception {
        var skillDir = skillsSourceDir.resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: " + name + "\n---\n# " + name);
    }

    private SkillDefinition testSkill(String name) {
        return new SkillDefinition(name, "Test skill", null, null, List.of(), Map.of(),
            null, null, null, null, null, null, null, List.of(), null, "# Test");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.sandbox.WorkspaceProvisionerTest" 2>&1 | tail -10`
Expected: FAIL — constructor signature changed

- [ ] **Step 3: Rewrite WorkspaceProvisioner**

Replace `src/main/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisioner.java`:

```java
package io.github.samzhu.grimo.shared.sandbox;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 派遣 agent 前準備工作目錄：建立 git worktree + 將 Grimo 管理的 Skill symlink 到
 * .agents/skills/（跨 agent 標準路徑），讓 CLI agent 原生發現。
 *
 * 設計說明：
 * - 統一模式：每次派遣都建獨立 git worktree（不分單一/多 agent）
 * - Worktree 提供 3 個價值：(1) 隔離 agent 修改 (2) 為 F4 並行打基礎 (3) 使用者可先看 diff 再 merge
 * - 非 git 目錄自動 fallback 到 CWD + symlink（現有行為）
 * - 永遠不拋例外：worktree 建立失敗時 fallback 到 CWD（WARN log）
 * - cleanup 前自動 commit 未提交變更，避免丟失 agent 的工作
 * - 分支保留讓使用者可以 git merge grimo/<taskId>
 *
 * @see <a href="https://git-scm.com/docs/git-worktree">Git Worktree Documentation</a>
 * @see <a href="https://github.com/GoogleCloudPlatform/scion">Google Scion — worktree per agent</a>
 * @see <a href="https://agentskills.io/client-implementation/adding-skills-support">
 *      Agent Skills — .agents/skills/ cross-client convention</a>
 */
public class WorkspaceProvisioner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceProvisioner.class);
    private static final String AGENTS_SKILLS_DIR = ".agents/skills";
    private final Path skillsSourceDir;
    private final GitHelper gitHelper;

    public WorkspaceProvisioner(Path skillsSourceDir, GitHelper gitHelper) {
        this.skillsSourceDir = skillsSourceDir;
        this.gitHelper = gitHelper;
    }

    /**
     * 準備 agent 工作區：建立 git worktree + provision skills。
     * 非 git 目錄或 worktree 建立失敗時 fallback 到 CWD + symlink。
     * 永遠不拋例外 — 失敗時回傳 isWorktree=false 的 WorktreeInfo。
     *
     * @param projectDir 使用者的專案目錄
     * @param taskId 任務 ID（用於分支名稱 grimo/<taskId>）
     * @param skills 要配置的 skill 列表
     * @return WorktreeInfo 包含工作目錄、分支、baseSha、已配置 skill
     */
    public WorktreeInfo provision(Path projectDir, String taskId, List<SkillDefinition> skills) {
        // 嘗試建立 git worktree
        if (gitHelper.isGitRepo(projectDir)) {
            try {
                String baseSha = gitHelper.getHeadSha(projectDir);
                // 設計說明：使用 taskId 組成目錄名稱，避免 createTempDirectory + delete 的 TOCTOU 競爭
                // git worktree add 需要目錄不存在，它會自己建立
                Path worktreeDir = Path.of(System.getProperty("java.io.tmpdir"),
                        "grimo-worktree-" + taskId);
                String branchName = "grimo/" + taskId;
                gitHelper.createWorktree(projectDir, worktreeDir, branchName);

                // 在 worktree 裡配置 skills
                List<String> provisioned = provisionSkills(worktreeDir, skills);

                return new WorktreeInfo(worktreeDir, branchName, baseSha, provisioned, true);
            } catch (Exception e) {
                log.warn("Failed to create worktree: {}, falling back to CWD", e.getMessage());
            }
        } else {
            log.warn("Not a git repository, falling back to CWD mode");
        }

        // Fallback: CWD + symlink（現有行為）
        List<String> provisioned = provisionSkills(projectDir, skills);
        return new WorktreeInfo(projectDir, null, null, provisioned, false);
    }

    /**
     * 清理工作區：移除 worktree 目錄 + skill symlinks。
     * 保留分支（讓使用者可以 merge）。
     *
     * @param info provision() 回傳的 WorktreeInfo
     * @param projectDir 使用者的專案目錄（worktree 模式需要在主 repo 執行 git worktree remove）
     */
    public void cleanup(WorktreeInfo info, Path projectDir) {
        if (info.isWorktree()) {
            try {
                // 清理前先檢查未提交變更
                if (gitHelper.hasUncommittedChanges(info.workDir())) {
                    gitHelper.autoCommit(info.workDir());
                }
                // removeWorktree --force 會刪除整個 worktree 目錄（包含 .agents/skills/ symlinks）
                // 所以 worktree 模式不需要單獨呼叫 cleanupSymlinks
                gitHelper.removeWorktree(projectDir, info.workDir());
            } catch (Exception e) {
                log.warn("Failed to cleanup worktree: {}", e.getMessage());
            }
        } else {
            // Fallback 模式：只移除 symlinks
            cleanupSymlinks(info.workDir(), info.provisionedSkills());
        }
    }

    /**
     * 將 skill symlink 到目標目錄的 .agents/skills/。
     */
    private List<String> provisionSkills(Path targetDir, List<SkillDefinition> skills) {
        if (skills.isEmpty()) return List.of();

        var provisioned = new ArrayList<String>();
        Path agentsSkillsDir = targetDir.resolve(AGENTS_SKILLS_DIR);

        try {
            Files.createDirectories(agentsSkillsDir);
        } catch (IOException e) {
            log.error("Failed to create .agents/skills/ directory: {}", e.getMessage());
            return List.of();
        }

        for (var skill : skills) {
            Path sourceSkillDir = skillsSourceDir.resolve(skill.name());
            if (!Files.isDirectory(sourceSkillDir)) {
                log.debug("Skill source directory not found, skipping: {}", sourceSkillDir);
                continue;
            }

            Path targetSkillDir = agentsSkillsDir.resolve(skill.name());
            if (Files.exists(targetSkillDir)) {
                log.warn("Skill '{}' already exists in .agents/skills/, skipping Grimo version", skill.name());
                continue;
            }

            try {
                Files.createSymbolicLink(targetSkillDir, sourceSkillDir);
                provisioned.add(skill.name());
                log.debug("Symlinked skill: {} -> {}", skill.name(), sourceSkillDir);
            } catch (IOException e) {
                log.warn("Failed to symlink skill '{}': {}", skill.name(), e.getMessage());
            }
        }

        if (!provisioned.isEmpty()) {
            log.info("Provisioned {} skills to .agents/skills/: [{}]",
                    provisioned.size(), String.join(", ", provisioned));
        }

        return provisioned;
    }

    /**
     * 移除 Grimo 建立的 symlinks（不刪使用者 skill）。
     */
    private void cleanupSymlinks(Path targetDir, List<String> provisionedSkillNames) {
        Path agentsSkillsDir = targetDir.resolve(AGENTS_SKILLS_DIR);
        int removed = 0;
        for (var name : provisionedSkillNames) {
            Path symlink = agentsSkillsDir.resolve(name);
            if (Files.isSymbolicLink(symlink)) {
                try {
                    Files.delete(symlink);
                    removed++;
                } catch (IOException e) {
                    log.warn("Failed to cleanup symlink '{}': {}", name, e.getMessage());
                }
            }
        }
        if (removed > 0) {
            log.debug("Cleaned up workspace: removed {} symlinks", removed);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.sandbox.WorkspaceProvisionerTest" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL — all 10 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisioner.java \
       src/test/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisionerTest.java
git commit -m "refactor(f2e): WorkspaceProvisioner with git worktree lifecycle"
```

---

### Task 4: Update Spring Bean Wiring

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java:86-94`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java` (constructor only)

> **Why this task comes before TUI changes:** GrimoTuiRunner needs `GitHelper` injected.
> Bean wiring must be in place before the TUI code can reference `gitHelper`.

- [ ] **Step 1: Add GitHelper bean to GrimoStartupRunner**

In `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`, add new bean and update existing bean:

```java
@Bean
GitHelper gitHelper() {
    return new GitHelper();
}

@Bean
WorkspaceProvisioner workspaceProvisioner(WorkspaceManager workspaceManager, GitHelper gitHelper) {
    return new WorkspaceProvisioner(workspaceManager.skillsDir(), gitHelper);
}
```

- [ ] **Step 2: Add GitHelper to GrimoTuiRunner constructor**

In `GrimoTuiRunner.java`:
- Add import: `import io.github.samzhu.grimo.shared.sandbox.GitHelper;`
- Add field: `private final GitHelper gitHelper;`
- Add constructor parameter: `GitHelper gitHelper,` (after `workspaceProvisioner`)
- Add to constructor body: `this.gitHelper = gitHelper;`

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java \
       src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "feat(f2e): wire GitHelper bean into WorkspaceProvisioner and GrimoTuiRunner"
```

---

### Task 5: Update GrimoTuiRunner for Worktree Flow + Diff Summary

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`

> **Note:** This task has no separate unit test because GrimoTuiRunner is an integration component (ApplicationRunner + TUI event loop). Verification is manual via `run.sh`.

- [ ] **Step 1: Add imports to GrimoTuiRunner**

```java
import io.github.samzhu.grimo.shared.sandbox.GitHelper;
import io.github.samzhu.grimo.shared.sandbox.WorktreeInfo;
```

- [ ] **Step 2: Rewrite agent dispatch flow in processInput()**

Replace the virtual thread block (lines ~553-619) in `processInput()`:

```java
agentThread = Thread.startVirtualThread(() -> {
    long startTime = System.currentTimeMillis();
    // 設計說明：統一 worktree 模式 — 每次派遣都在獨立 git worktree 工作
    // 非 git 目錄 fallback 到 CWD（現有行為）
    // 參考：Google Scion — 每個 agent 一個 container + git worktree
    var projectDir = java.nio.file.Path.of(System.getProperty("user.dir"));
    var taskId = java.util.UUID.randomUUID().toString().substring(0, 8);
    var worktree = workspaceProvisioner.provision(
            projectDir, taskId, skillRegistry.listAll());
    try {
        // 移除 "thinking..." 暫時狀態行（在顯示 skill 之前）
        contentView.removeLastLine();

        // 在 Content 區顯示已配置的 skill（對齊 Claude Code 風格）
        for (var skillName : worktree.provisionedSkills()) {
            var nameLine = new org.jline.utils.AttributedStringBuilder();
            nameLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(2), "● ");
            nameLine.append("Skill(" + skillName + ")");
            contentView.appendLine(nameLine.toAttributedString());

            var statusLine = new org.jline.utils.AttributedStringBuilder();
            statusLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                    "  └ Successfully loaded skill");
            contentView.appendLine(statusLine.toAttributedString());
            eventLoop.setDirty();
        }

        // 顯示 worktree 資訊（只有 worktree 模式）
        if (worktree.isWorktree()) {
            var wtLine = new org.jline.utils.AttributedStringBuilder();
            wtLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(2), "● ");
            wtLine.append("Worktree(" + worktree.branchName() + ")");
            contentView.appendLine(wtLine.toAttributedString());

            var wtStatus = new org.jline.utils.AttributedStringBuilder();
            wtStatus.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                    "  └ Isolated workspace created");
            contentView.appendLine(wtStatus.toAttributedString());
            eventLoop.setDirty();
        }

        // 設計說明：workingDirectory 指向 worktree（隔離模式）或 CWD（fallback）
        var client = AgentClient.builder(model)
                .mcpServerCatalog(mcpCatalogBuilder.getCatalog())
                .defaultMcpServers(mcpCatalogBuilder.getServerNames())
                .defaultWorkingDirectory(worktree.workDir())
                .build();
        var response = client.run(text, tierOptions);

        long duration = System.currentTimeMillis() - startTime;
        if (response.isSuccessful()) {
            log.info("Agent response received: success=true, duration={}ms, resultLength={}",
                    duration, response.getResult() != null ? response.getResult().length() : 0);

            // 設計說明：worktree 模式 + agent 有 commit → 顯示 diff 摘要
            // 讓使用者知道 agent 改了什麼、在哪個分支、如何 merge
            if (worktree.isWorktree()) {
                displayDiffSummary(projectDir, worktree, duration);
            } else {
                contentView.appendAiReply(response.getResult());
            }

            // worktree 模式下也顯示 agent 的文字回覆（如果有的話）
            if (worktree.isWorktree() && response.getResult() != null
                    && !response.getResult().isBlank()) {
                contentView.appendAiReply(response.getResult());
            }
        } else {
            log.warn("Agent response received: success=false, duration={}ms, result={}",
                    duration, response.getResult());
            contentView.appendError(response.getResult());
        }
        sessionWriter.writeAssistantMessage(response.getResult());
    } catch (Exception e) {
        long duration = System.currentTimeMillis() - startTime;
        log.error("Agent call failed: duration={}ms, error={}", duration, e.getMessage(), e);
        String errorMsg = formatAgentError(e);
        contentView.appendError(errorMsg);
    } finally {
        agentRunning = false;
        agentThread = null;
        currentTierSelection = null;
        statusView.setStatusText(originalStatusText);
        // 清理 worktree 或 symlinks
        workspaceProvisioner.cleanup(worktree, projectDir);
        screen.requestFullRedraw();
        eventLoop.setDirty();
    }
});
```

- [ ] **Step 3: Add displayDiffSummary method**

Add this method to `GrimoTuiRunner`:

```java
/**
 * Agent 完成後顯示 diff 摘要（只有 worktree 模式 + 有 commits 時）。
 *
 * 設計說明：
 * - 使用 baseSha（worktree 建立時的 HEAD）做 diff 比較，不依賴分支名稱
 * - 純對話（無 commit）不顯示 branch/diff 資訊
 * - 顯示 merge 指令方便使用者操作
 */
private void displayDiffSummary(Path projectDir, WorktreeInfo worktree, long durationMs) {
    try {
        int commitCount = gitHelper.getCommitCount(
                projectDir, worktree.baseSha(), worktree.branchName());
        if (commitCount == 0) {
            return; // 純對話，不顯示 diff
        }

        String diffStat = gitHelper.getDiffStat(
                projectDir, worktree.baseSha(), worktree.branchName());
        float seconds = durationMs / 1000f;

        // ⏺ Agent 完成 (12s)
        var headerLine = new org.jline.utils.AttributedStringBuilder();
        headerLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(2),
                "⏺ Agent completed (" + String.format("%.0fs", seconds) + ")");
        contentView.appendLine(headerLine.toAttributedString());

        // Branch: grimo/abc123
        var branchLine = new org.jline.utils.AttributedStringBuilder();
        branchLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                "  Branch: " + worktree.branchName());
        contentView.appendLine(branchLine.toAttributedString());

        // Files changed: ... (from diffStat)
        if (!diffStat.isBlank()) {
            for (String line : diffStat.split("\n")) {
                var diffLine = new org.jline.utils.AttributedStringBuilder();
                diffLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                        "  " + line.trim());
                contentView.appendLine(diffLine.toAttributedString());
            }
        }

        // Commits: N
        var commitLine = new org.jline.utils.AttributedStringBuilder();
        commitLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                "  Commits: " + commitCount);
        contentView.appendLine(commitLine.toAttributedString());

        // → git merge grimo/abc123
        var mergeLine = new org.jline.utils.AttributedStringBuilder();
        mergeLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(67),
                "  → git merge " + worktree.branchName());
        contentView.appendLine(mergeLine.toAttributedString());

        eventLoop.setDirty();
    } catch (Exception e) {
        log.warn("Failed to display diff summary: {}", e.getMessage());
    }
}
```

- [ ] **Step 4: Compile check**

Run: `./gradlew compileJava 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "feat(f2e): worktree isolation in agent dispatch with diff summary"
```

---

### Task 6: Update Glossary

**Files:**
- Modify: `docs/glossary.md`

- [ ] **Step 1: Add Worktree terms to glossary**

Add to the "調度系統術語" table in `docs/glossary.md`:

```markdown
| **Worktree** | Worktree | 每次 agent 派遣時建立的獨立 git worktree。Agent 在 worktree 中工作，完成後使用者決定是否 merge。非 git 目錄 fallback 到 CWD（現有行為）。 |
| **WorktreeInfo** | Worktree Info | `WorktreeInfo(workDir, branchName, baseSha, provisionedSkills, isWorktree)` record。記錄 worktree 的工作目錄、分支名稱、建立時的 HEAD SHA、已配置 skill。 |
| **GitHelper** | Git Helper | Git CLI 操作工具。封裝 worktree 建立/移除、diff、auto-commit 等操作。使用 ProcessBuilder 執行 git 指令。 |
```

Update the "Agent 技術元件對應" table:

```markdown
| Worktree 隔離 | `WorkspaceProvisioner` + `GitHelper` | `git worktree add/remove` via ProcessBuilder |
```

- [ ] **Step 2: Commit**

```bash
git add docs/glossary.md
git commit -m "docs: add Worktree/WorktreeInfo/GitHelper terms to glossary"
```

---

### Task 7: Manual Verification

- [ ] **Step 1: Build and run**

```bash
./run.sh
```

- [ ] **Step 2: Test in git repo (worktree mode)**

Type: `fix the TODO in README.md`

Expected TUI output:
```
● Skill(code-review)
  └ Successfully loaded skill
● Worktree(grimo/abc12345)
  └ Isolated workspace created
⏳ thinking...

⏺ Agent completed (12s)
  Branch: grimo/abc12345
  Files changed: 1 (+2 -1)
  Commits: 1
  → git merge grimo/abc12345
```

- [ ] **Step 3: Verify worktree cleanup**

```bash
# worktree 目錄應該已清理
ls /tmp/grimo-worktree-*  # should not exist

# 分支應該保留
git branch | grep grimo/  # should show the branch
```

- [ ] **Step 4: Test pure conversation (no file changes)**

Type: `hello`

Expected: normal AI reply, no branch/diff info shown.

- [ ] **Step 5: Test in non-git directory (fallback mode)**

```bash
cd /tmp && java -jar /path/to/grimo.jar
```

Expected: WARN log `Not a git repository, falling back to CWD mode`, normal behavior.

---

## SDK Limitations & Notes

| Agent | Docker Sandbox | Worktree Isolation | Notes |
|-------|---------------|-------------------|-------|
| Claude | NOT supported (ClaudeAgentModel has no `sandbox()`) | Supported via `workingDirectory(Path)` | Worktree is the ONLY isolation for Claude in local mode |
| Gemini | Supported (constructor accepts `Sandbox`) | Supported via `workingDirectory(Path)` | Phase B: use `DockerSandbox` when mode=docker |
| Codex | Supported (constructor accepts `Sandbox`) | Supported via `workingDirectory(Path)` | Phase B: use `DockerSandbox` when mode=docker |

> **User requirement:** "Gemini/Codex/Claude 都可以 in Docker Sandbox" — Claude 目前 SDK 不支援 Docker Sandbox（ClaudeAgentModel 沒有 sandbox 參數）。需要等 upstream SDK 更新，或自行包裝 decorator。此計劃先用 worktree 隔離（對三個 agent 都適用），Docker 隔離為 Phase B 獨立處理。
