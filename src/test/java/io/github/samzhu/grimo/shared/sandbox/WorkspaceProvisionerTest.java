package io.github.samzhu.grimo.shared.sandbox;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceProvisionerTest {

    @TempDir Path tempDir;
    @TempDir Path skillsSourceDir;

    /** 追蹤已建立的 worktree，確保測試後清理 */
    private final List<WorktreeInfo> createdWorktrees = new ArrayList<>();
    private Path lastRepoDir;

    @AfterEach
    void cleanupWorktrees() {
        var helper = new GitHelper();
        for (var info : createdWorktrees) {
            if (info.isWorktree() && Files.exists(info.workDir()) && lastRepoDir != null) {
                try {
                    helper.removeWorktree(lastRepoDir, info.workDir());
                } catch (Exception e) {
                    // best-effort cleanup
                }
            }
        }
        createdWorktrees.clear();
    }

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
        lastRepoDir = repo;
        setupSkillSource("code-review");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir, new GitHelper());
        var info = provisioner.provision(repo, "task-001", List.of(testSkill("code-review")));
        createdWorktrees.add(info);

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
        lastRepoDir = repo;
        var provisioner = new WorkspaceProvisioner(skillsSourceDir, new GitHelper());
        var info = provisioner.provision(repo, "task-002", List.of());
        createdWorktrees.add(info);

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
        lastRepoDir = repo;
        var provisioner = new WorkspaceProvisioner(skillsSourceDir, new GitHelper());
        var info = provisioner.provision(repo, "task-007", List.of());
        createdWorktrees.add(info);

        assertThat(info.isWorktree()).isTrue();
        assertThat(info.provisionedSkills()).isEmpty();
    }

    @Test
    void provisionShouldFallbackWhenWorktreeCreationFails() throws Exception {
        Path repo = createGitRepo();
        lastRepoDir = repo;
        // 建立同名分支讓 worktree add 失敗
        exec(repo, "git", "branch", "grimo/conflict-task");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir, new GitHelper());
        var info = provisioner.provision(repo, "conflict-task", List.of());
        createdWorktrees.add(info);

        assertThat(info.isWorktree()).isFalse();
        assertThat(info.workDir()).isEqualTo(repo);
    }

    @Test
    void provisionShouldSkipConflictingUserSkillInWorktree() throws Exception {
        Path repo = createGitRepo();
        lastRepoDir = repo;
        // 先在 repo 建立使用者自己的 skill
        Path userSkillDir = repo.resolve(".agents/skills/code-review");
        Files.createDirectories(userSkillDir);
        Files.writeString(userSkillDir.resolve("SKILL.md"), "user version");
        exec(repo, "git", "add", ".");
        exec(repo, "git", "commit", "-m", "add user skill");

        setupSkillSource("code-review");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir, new GitHelper());
        var info = provisioner.provision(repo, "task-008", List.of(testSkill("code-review")));
        createdWorktrees.add(info);

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
