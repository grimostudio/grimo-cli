package io.github.samzhu.grimo.shared.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionWriter 單元測試：驗證 JSONL append 與 dispatch meta.json 寫入。
 */
class SessionWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writeDispatchEnteredShouldAppendToSessionFile() throws Exception {
        var dataDir = tempDir.resolve("project-data");
        Files.createDirectories(dataDir);
        var writer = new SessionWriter(dataDir);
        writer.writeSystemMessage("/test", "1.0", "test");

        writer.writeDispatchEntered("abc123", "claude", "claude-sonnet-4-6", "std", "grimo/abc123", "fix bug");

        var content = Files.readString(writer.getSessionFile());
        assertThat(content).contains("dispatch-entered");
        assertThat(content).contains("abc123");
        assertThat(content).contains("claude-sonnet-4-6");
    }

    @Test
    void writeDispatchCompletedShouldCreateMetaJson() throws Exception {
        var dataDir = tempDir.resolve("project-data");
        Files.createDirectories(dataDir);
        var writer = new SessionWriter(dataDir);
        writer.writeSystemMessage("/test", "1.0", "test");

        writer.writeDispatchCompleted("abc123", "claude", "claude-sonnet-4-6", "std",
                "fix bug", "worktree", "/tmp/wt", "grimo/abc123", "sha123", null,
                true, 3, "5 files changed", 45000, "Fixed bug", null);

        assertThat(Files.readString(writer.getSessionFile()))
                .contains("dispatch-completed").contains("abc123");

        var metaFile = writer.dispatchesDir().resolve("abc123.meta.json");
        assertThat(metaFile).exists();
        assertThat(Files.readString(metaFile))
                .contains("claude-sonnet-4-6")
                .contains("worktree")
                .contains("Fixed bug");
    }

    @Test
    void writeDispatchCompletedWithExternalSessionShouldIncludeIt() throws Exception {
        var dataDir = tempDir.resolve("project-data");
        Files.createDirectories(dataDir);
        var writer = new SessionWriter(dataDir);
        writer.writeSystemMessage("/test", "1.0", "test");

        writer.writeDispatchCompleted("task456", "claude", "claude-sonnet-4-6", "std",
                "add feature", "worktree", "/tmp/wt2", "grimo/task456", "sha456", "ctr-789",
                true, 1, "2 files changed", 30000, "Added feature",
                "/home/user/.claude/sessions/ext-session.jsonl");

        var metaFile = writer.dispatchesDir().resolve("task456.meta.json");
        assertThat(metaFile).exists();
        var metaContent = Files.readString(metaFile);
        assertThat(metaContent).contains("externalSessions");
        assertThat(metaContent).contains("ext-session.jsonl");
        assertThat(metaContent).contains("ctr-789");
    }

    @Test
    void writeDispatchEnteredShouldChainParentUuid() throws Exception {
        var dataDir = tempDir.resolve("project-data");
        Files.createDirectories(dataDir);
        var writer = new SessionWriter(dataDir);
        writer.writeSystemMessage("/test", "1.0", "test");

        writer.writeDispatchEntered("t1", "claude", "opus", "std", "branch1", "goal1");
        writer.writeDispatchEntered("t2", "claude", "opus", "std", "branch2", "goal2");

        var lines = Files.readAllLines(writer.getSessionFile());
        // 3 lines: system, dispatch-entered t1, dispatch-entered t2
        assertThat(lines).hasSize(3);
        // Each subsequent line should have parentUuid referencing the previous
        assertThat(lines.get(1)).contains("parentUuid");
        assertThat(lines.get(2)).contains("parentUuid");
    }

    @Test
    void initShouldSwitchTargetFile() throws Exception {
        var dataDir = tempDir.resolve("project-data");
        Files.createDirectories(dataDir);
        var writer = new SessionWriter(dataDir);

        writer.init("test1234", dataDir.resolve("test1234.jsonl"));
        writer.writeSystemMessage("/test", "1.0", "test");

        assertThat(writer.getSessionId()).isEqualTo("test1234");
        assertThat(writer.getSessionFile()).isEqualTo(dataDir.resolve("test1234.jsonl"));
        assertThat(writer.getSessionFile()).exists();
    }

    @Test
    void readAllMessagesShouldParseJsonl() throws Exception {
        var dataDir = tempDir.resolve("project-data");
        Files.createDirectories(dataDir);
        var writer = new SessionWriter(dataDir);
        writer.init("read-test", dataDir.resolve("read-test.jsonl"));
        writer.writeSystemMessage("/test", "1.0", "test");
        writer.writeUserMessage("hello");
        writer.writeAssistantMessage("world");

        var messages = writer.readAllMessages(writer.getSessionFile());

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).type()).isEqualTo("system");
        assertThat(messages.get(1).type()).isEqualTo("user");
        assertThat(messages.get(1).content()).isEqualTo("hello");
        assertThat(messages.get(2).type()).isEqualTo("assistant");
        assertThat(messages.get(2).content()).isEqualTo("world");
    }

    @Test
    void callbackShouldBeInvokedOnWrite() throws Exception {
        var dataDir = tempDir.resolve("project-data");
        Files.createDirectories(dataDir);
        var writer = new SessionWriter(dataDir);
        writer.init("cb-test", dataDir.resolve("cb-test.jsonl"));

        var types = new java.util.ArrayList<String>();
        var contents = new java.util.ArrayList<String>();
        writer.setWriteCallback((type, content) -> {
            types.add(type);
            contents.add(content);
        });

        writer.writeUserMessage("test input");
        writer.writeAssistantMessage("test reply");

        assertThat(types).containsExactly("user", "assistant");
        assertThat(contents.get(0)).isEqualTo("test input");
    }
}
