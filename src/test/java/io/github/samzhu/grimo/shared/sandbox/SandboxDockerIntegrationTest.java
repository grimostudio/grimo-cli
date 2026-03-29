package io.github.samzhu.grimo.shared.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springaicommunity.sandbox.ExecSpec;
import org.springaicommunity.sandbox.FileSpec;
import org.springaicommunity.sandbox.docker.DockerSandbox;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Docker 整合測試：驗證 SandboxDetector 偵測 + DockerSandbox skill 配置可行性。
 *
 * 設計說明：
 * - 只在 Docker daemon 可用時執行（@EnabledIf）
 * - Part A：驗證 SandboxDetector.checkDocker() 正確偵測
 * - Part B：驗證 DockerSandbox 可用 SandboxFiles 寫入 SKILL.md（Phase B 可行性驗證）
 * - Docker 不可用時自動跳過，不影響 CI/CD
 */
@EnabledIf("isDockerAvailable")
class SandboxDockerIntegrationTest {

    static boolean isDockerAvailable() {
        try {
            var process = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // === Part A: SandboxDetector Docker 偵測 ===

    @Test
    void detectShouldFindDockerAvailable() {
        var detector = new SandboxDetector();
        var result = detector.detect();
        assertThat(result.dockerAvailable()).isTrue();
    }

    @Test
    void resolveModeShouldReturnDockerWhenDaemonRunning() {
        var detector = new SandboxDetector();
        var result = detector.detect();
        assertThat(detector.resolveMode(result, "docker")).isEqualTo("docker");
    }

    // === Part B: DockerSandbox Skill 配置可行性（Phase B 驗證）===

    @Test
    void dockerSandboxShouldWriteSkillFileViaSandboxFiles() {
        String skillContent = """
                ---
                name: test-skill
                description: A test skill for Docker sandbox validation
                ---

                # Test Skill

                This skill verifies Docker sandbox file operations work correctly.
                """;

        try (var sandbox = DockerSandbox.builder().build()) {
            // 用 SandboxFiles 寫入 skill 到 .agents/skills/
            sandbox.files()
                    .createDirectory(".agents/skills/test-skill")
                    .create(".agents/skills/test-skill/SKILL.md", skillContent);

            // 驗證檔案存在
            assertThat(sandbox.files().exists(".agents/skills/test-skill/SKILL.md")).isTrue();

            // 用 exec 讀回內容驗證
            var result = sandbox.exec(ExecSpec.of("cat", "/work/.agents/skills/test-skill/SKILL.md"));
            assertThat(result.success()).isTrue();
            assertThat(result.stdout()).contains("name: test-skill");
            assertThat(result.stdout()).contains("# Test Skill");
        }
    }

    @Test
    void dockerSandboxShouldSupportMultipleSkills() {
        try (var sandbox = DockerSandbox.builder()
                .withFiles(List.of(
                        FileSpec.of(".agents/skills/skill-a/SKILL.md",
                                "---\nname: skill-a\ndescription: Skill A\n---\n# A"),
                        FileSpec.of(".agents/skills/skill-b/SKILL.md",
                                "---\nname: skill-b\ndescription: Skill B\n---\n# B")
                ))
                .build()) {

            // 驗證兩個 skill 都存在
            assertThat(sandbox.files().exists(".agents/skills/skill-a/SKILL.md")).isTrue();
            assertThat(sandbox.files().exists(".agents/skills/skill-b/SKILL.md")).isTrue();

            // 列出 skills 目錄
            var entries = sandbox.files().list(".agents/skills");
            assertThat(entries).hasSize(2);
        }
    }
}
