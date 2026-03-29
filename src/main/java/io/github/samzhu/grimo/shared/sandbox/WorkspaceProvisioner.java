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
 * 派遣 agent 前準備工作目錄：將 Grimo 管理的 Skill symlink 到
 * .agents/skills/（跨 agent 標準路徑），讓 CLI agent 原生發現。
 *
 * 設計說明：
 * - 使用 .agents/skills/ 路徑，Claude Code / Gemini CLI / Codex CLI 皆掃描
 * - symlink 而非複製，避免重複檔案
 * - 名稱衝突時以使用者的為優先，跳過 Grimo 版本（WARN log）
 * - cleanup() 只移除 Grimo 建立的 symlink，不刪除使用者自己的 skill
 * - 併發安全：GrimoTuiRunner 已有 agentRunning 守衛，同時只有一個 agent 執行
 *
 * @see <a href="https://agentskills.io/client-implementation/adding-skills-support">
 *      Agent Skills — .agents/skills/ cross-client convention</a>
 */
public class WorkspaceProvisioner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceProvisioner.class);
    private static final String AGENTS_SKILLS_DIR = ".agents/skills";
    private final Path skillsSourceDir;

    public WorkspaceProvisioner(Path skillsSourceDir) {
        this.skillsSourceDir = skillsSourceDir;
    }

    public List<String> provision(Path projectDir, List<SkillDefinition> skills) {
        if (skills.isEmpty()) return List.of();

        var provisioned = new ArrayList<String>();
        Path targetDir = projectDir.resolve(AGENTS_SKILLS_DIR);

        try {
            Files.createDirectories(targetDir);
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

            Path targetSkillDir = targetDir.resolve(skill.name());
            if (Files.exists(targetSkillDir)) {
                log.warn("Skill '{}' already exists in project .agents/skills/, skipping Grimo version", skill.name());
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

    public void cleanup(Path projectDir, List<String> provisionedSkillNames) {
        Path targetDir = projectDir.resolve(AGENTS_SKILLS_DIR);
        int removed = 0;

        for (var name : provisionedSkillNames) {
            Path symlink = targetDir.resolve(name);
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
