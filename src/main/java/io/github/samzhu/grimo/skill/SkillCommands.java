package io.github.samzhu.grimo.skill;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import io.github.samzhu.grimo.skill.loader.SkillLoader;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spring Shell CLI commands for managing skills.
 * Provides 'skill list', 'skill remove', and 'skill install' commands
 * to view, unregister, and install skills from Git repositories.
 *
 * Uses Spring Shell 4.0 @Command annotation model (replaces legacy @ShellComponent/@ShellMethod).
 * Reference: https://github.com/spring-projects/spring-shell/wiki/v4-migration-guide
 */
@Component
public class SkillCommands {

    private final SkillRegistry registry;
    private final Path skillsDir;

    public SkillCommands(SkillRegistry registry, Path skillsDir) {
        this.registry = registry;
        this.skillsDir = skillsDir;
    }

    /**
     * 裸指令別名：輸入 /skill 等同 /skill list，符合漸進式揭露原則。
     * Smart Default: bare 'skill' command delegates to 'skill list'.
     */
    @Command(name = "skill", description = "List all loaded skills (alias for 'skill list')")
    public String skillDefault() {
        return list();
    }

    @Command(name = {"skill", "list"}, description = "List all loaded skills")
    public String list() {
        var skills = registry.listAll();
        if (skills.isEmpty()) return "No skills loaded.";

        var sb = new StringBuilder();
        sb.append(String.format("  %-15s %-10s %-15s %-8s%n", "NAME", "VERSION", "AUTHOR", "STATUS"));
        for (SkillDefinition s : skills) {
            sb.append(String.format("  %-15s %-10s %-15s %-8s%n",
                s.name(), s.version(), s.author(), "loaded"));
        }
        return sb.toString();
    }

    @Command(name = {"skill", "remove"}, description = "Remove a loaded skill")
    public String remove(String name) {
        if (registry.get(name).isEmpty()) {
            return "Skill not found: " + name;
        }
        registry.remove(name);
        return "Skill removed: " + name;
    }

    /**
     * Installs a skill by cloning its Git repository into the workspace skills directory,
     * then loads and registers the SKILL.md definition found in the cloned repo.
     * Convention: repository name prefix 'grimo-skill-' is stripped for the skill directory name.
     */
    @Command(name = {"skill", "install"}, description = "Install a skill from a Git repository URL")
    public String install(String url) {
        String skillName = url.substring(url.lastIndexOf('/') + 1)
            .replace("grimo-skill-", "");
        Path targetDir = skillsDir.resolve(skillName);

        try {
            var process = new ProcessBuilder("git", "clone", url, targetDir.toString())
                .redirectErrorStream(true)
                .start();
            int exit = process.waitFor();
            if (exit != 0) {
                String error = new String(process.getInputStream().readAllBytes());
                return "Failed to install skill: " + error;
            }

            var loader = new SkillLoader(skillsDir);
            Path skillMd = targetDir.resolve("SKILL.md");
            if (Files.exists(skillMd)) {
                var skill = loader.load(skillMd);
                registry.register(skill);
                return skillName + " skill installed to skills/" + skillName + "/";
            } else {
                return "Warning: skill installed but no SKILL.md found in " + targetDir;
            }
        } catch (Exception e) {
            return "Failed to install skill: " + e.getMessage();
        }
    }
}
