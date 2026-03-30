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
