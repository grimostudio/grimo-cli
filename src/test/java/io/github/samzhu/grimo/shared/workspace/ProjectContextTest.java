package io.github.samzhu.grimo.shared.workspace;

import io.github.samzhu.grimo.home.GrimoHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectContextTest {

    @TempDir
    Path tempHome;

    @Test
    void dataDirShouldBeUnderProjectsWithEncodedCwd() {
        var home = new GrimoHome(tempHome);
        var cwd = Path.of("/Users/samzhu/workspace/grimo-cli");
        var ctx = new ProjectContext(home, cwd);
        assertThat(ctx.dataDir()).isEqualTo(
            tempHome.resolve("projects").resolve("-Users-samzhu-workspace-grimo-cli"));
    }

    @Test
    void displayPathShouldReplaceHomeWithTilde() {
        var home = new GrimoHome(tempHome);
        var userHome = System.getProperty("user.home");
        var cwd = Path.of(userHome, "workspace", "grimo-cli");
        var ctx = new ProjectContext(home, cwd);
        assertThat(ctx.displayPath()).isEqualTo("~/workspace/grimo-cli");
    }

    @Test
    void displayPathShouldReturnFullPathWhenNotUnderHome() {
        var home = new GrimoHome(tempHome);
        var cwd = Path.of("/opt/projects/grimo");
        var ctx = new ProjectContext(home, cwd);
        assertThat(ctx.displayPath()).isEqualTo("/opt/projects/grimo");
    }

    @Test
    void pathShouldReturnOriginalCwd() {
        var home = new GrimoHome(tempHome);
        var cwd = Path.of("/Users/samzhu/workspace/grimo-cli");
        var ctx = new ProjectContext(home, cwd);
        assertThat(ctx.path()).isEqualTo(cwd);
    }

    @Test
    void initializeShouldCreateDataDir() {
        var home = new GrimoHome(tempHome);
        home.initialize();
        var cwd = Path.of("/Users/samzhu/workspace/grimo-cli");
        var ctx = new ProjectContext(home, cwd);
        ctx.initialize();
        assertThat(ctx.dataDir()).isDirectory();
    }
}
