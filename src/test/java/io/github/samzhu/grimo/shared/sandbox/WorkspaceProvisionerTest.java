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

    @TempDir Path projectDir;
    @TempDir Path skillsSourceDir;

    @Test
    void provisionShouldSymlinkSkillsToAgentsDirectory() throws Exception {
        var skillDir = skillsSourceDir.resolve("code-review");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: code-review\n---\n# CR");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir);
        var result = provisioner.provision(projectDir, List.of(testSkill("code-review")));

        assertThat(result).containsExactly("code-review");
        Path symlink = projectDir.resolve(".agents/skills/code-review");
        assertThat(Files.isSymbolicLink(symlink)).isTrue();
        assertThat(Files.readSymbolicLink(symlink)).isEqualTo(skillDir);
    }

    @Test
    void provisionShouldCreateAgentsSkillsDirectoryIfNotExists() throws Exception {
        var skillDir = skillsSourceDir.resolve("test-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: test-skill\n---\n# Test");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir);
        provisioner.provision(projectDir, List.of(testSkill("test-skill")));

        assertThat(Files.isDirectory(projectDir.resolve(".agents/skills"))).isTrue();
    }

    @Test
    void provisionShouldSkipIfSkillSourceDirNotExists() throws Exception {
        var provisioner = new WorkspaceProvisioner(skillsSourceDir);
        var result = provisioner.provision(projectDir, List.of(testSkill("nonexistent")));
        assertThat(result).isEmpty();
    }

    @Test
    void provisionShouldSkipConflictingUserSkill() throws Exception {
        var userSkillDir = projectDir.resolve(".agents/skills/code-review");
        Files.createDirectories(userSkillDir);
        Files.writeString(userSkillDir.resolve("SKILL.md"), "user version");

        var grimoSkillDir = skillsSourceDir.resolve("code-review");
        Files.createDirectories(grimoSkillDir);
        Files.writeString(grimoSkillDir.resolve("SKILL.md"), "grimo version");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir);
        var result = provisioner.provision(projectDir, List.of(testSkill("code-review")));

        assertThat(result).isEmpty();
        assertThat(Files.readString(userSkillDir.resolve("SKILL.md"))).isEqualTo("user version");
    }

    @Test
    void provisionShouldReturnEmptyForNoSkills() {
        var provisioner = new WorkspaceProvisioner(skillsSourceDir);
        var result = provisioner.provision(projectDir, List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void cleanupShouldRemoveProvisionedSymlinks() throws Exception {
        var skillDir = skillsSourceDir.resolve("code-review");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: code-review\n---\n# CR");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir);
        var provisioned = provisioner.provision(projectDir, List.of(testSkill("code-review")));
        provisioner.cleanup(projectDir, provisioned);

        assertThat(Files.exists(projectDir.resolve(".agents/skills/code-review"))).isFalse();
    }

    @Test
    void cleanupShouldNotRemoveUserSkills() throws Exception {
        var userSkillDir = projectDir.resolve(".agents/skills/user-skill");
        Files.createDirectories(userSkillDir);
        Files.writeString(userSkillDir.resolve("SKILL.md"), "user skill");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir);
        provisioner.cleanup(projectDir, List.of("user-skill"));

        assertThat(Files.exists(userSkillDir)).isTrue();
    }

    private SkillDefinition testSkill(String name) {
        return new SkillDefinition(name, "Test skill", null, null, List.of(), Map.of(),
            null, null, null, null, null, null, null, List.of(), null, "# Test");
    }
}
