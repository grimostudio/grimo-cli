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
     * 刪除本地分支（force delete）。
     * 用於純對話無檔案變更時，清理不需要的 worktree 分支。
     */
    public void deleteBranch(Path repoDir, String branchName) {
        exec(repoDir, "git", "branch", "-D", branchName);
        log.debug("Deleted branch: {}", branchName);
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
     * 取得當前 git branch 名稱。
     * 非 git repo 或發生錯誤時回傳 null。
     */
    public String getCurrentBranch(Path repoDir) {
        try {
            return exec(repoDir, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
        } catch (Exception e) {
            return null;
        }
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
