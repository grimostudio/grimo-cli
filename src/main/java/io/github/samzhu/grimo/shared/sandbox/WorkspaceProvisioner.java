package io.github.samzhu.grimo.shared.sandbox;

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
     * @param skillNames 要配置的 skill 名稱列表
     * @return WorktreeInfo 包含工作目錄、分支、baseSha、已配置 skill
     */
    public WorktreeInfo provision(Path projectDir, String taskId, List<String> skillNames) {
        // 嘗試建立 git worktree
        if (gitHelper.isGitRepo(projectDir)) {
            try {
                String baseSha = gitHelper.getHeadSha(projectDir);
                // 設計說明：使用 taskId 組成目錄名稱，避免 createTempDirectory + delete 的 TOCTOU 競爭
                // git worktree add 需要目錄不存在，它會自己建立
                Path worktreeDir = Path.of(System.getProperty("java.io.tmpdir"),
                        "grimo-worktree-" + taskId);
                // 設計說明：如果目錄已存在（上次 crash 沒清理），先刪除再建
                if (Files.exists(worktreeDir)) {
                    log.warn("Stale worktree directory found, cleaning up: {}", worktreeDir);
                    gitHelper.removeWorktree(projectDir, worktreeDir);
                }
                String branchName = "grimo/" + taskId;
                gitHelper.createWorktree(projectDir, worktreeDir, branchName);

                // 在 worktree 裡配置 skills
                List<String> provisioned = provisionSkills(worktreeDir, skillNames);

                return new WorktreeInfo(worktreeDir, branchName, baseSha, provisioned, true);
            } catch (Exception e) {
                log.warn("Failed to create worktree: {}, falling back to CWD", e.getMessage());
            }
        } else {
            log.warn("Not a git repository, falling back to CWD mode");
        }

        // Fallback: CWD + symlink（現有行為）
        List<String> provisioned = provisionSkills(projectDir, skillNames);
        return new WorktreeInfo(projectDir, null, null, provisioned, false);
    }

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

    /**
     * 將 skill symlink 到目標目錄的 .agents/skills/。
     */
    private List<String> provisionSkills(Path targetDir, List<String> skillNames) {
        if (skillNames.isEmpty()) return List.of();

        var provisioned = new ArrayList<String>();
        Path agentsSkillsDir = targetDir.resolve(AGENTS_SKILLS_DIR);

        try {
            Files.createDirectories(agentsSkillsDir);
        } catch (IOException e) {
            log.error("Failed to create .agents/skills/ directory: {}", e.getMessage());
            return List.of();
        }

        for (var name : skillNames) {
            Path sourceSkillDir = skillsSourceDir.resolve(name);
            if (!Files.isDirectory(sourceSkillDir)) {
                log.debug("Skill source directory not found, skipping: {}", sourceSkillDir);
                continue;
            }

            Path targetSkillDir = agentsSkillsDir.resolve(name);
            if (Files.exists(targetSkillDir)) {
                log.warn("Skill '{}' already exists in .agents/skills/, skipping Grimo version", name);
                continue;
            }

            try {
                Files.createSymbolicLink(targetSkillDir, sourceSkillDir);
                provisioned.add(name);
                log.debug("Symlinked skill: {} -> {}", name, sourceSkillDir);
            } catch (IOException e) {
                log.warn("Failed to symlink skill '{}': {}", name, e.getMessage());
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
