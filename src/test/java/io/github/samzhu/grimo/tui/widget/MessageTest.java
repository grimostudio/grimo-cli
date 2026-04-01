package io.github.samzhu.grimo.tui.widget;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MessageTest {

    @Test
    void inlineShouldReturnTwoLinesWithCorrectWidth() {
        var lines = Message.inline("● ", "Skill(code-review)",
                "Successfully loaded skill", 50);
        assertThat(lines).hasSize(2);
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(50);
        }
    }

    @Test
    void inlineShouldTruncateLongTitle() {
        var lines = Message.inline("● ", "Skill(very-long-skill-name-here)",
                "loaded", 30);
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(30);
        }
    }

    @Test
    void blockUserShouldHaveCorrectWidth() {
        var lines = Message.block(Message.Role.USER, "hello world", 40);
        assertThat(lines).isNotEmpty();
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(40);
        }
    }

    @Test
    void blockAgentShouldHaveCorrectWidth() {
        var lines = Message.block(Message.Role.AGENT, "I can help.", 40);
        assertThat(lines).isNotEmpty();
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(40);
        }
    }

    @Test
    void blockErrorShouldHaveCorrectWidth() {
        var lines = Message.block(Message.Role.ERROR, "timeout", 40);
        assertThat(lines).isNotEmpty();
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(40);
        }
    }

    @Test
    void blockShouldWrapLongContent() {
        String longText = "a".repeat(100);
        var lines = Message.block(Message.Role.AGENT, longText, 40);
        assertThat(lines.size()).isGreaterThan(1);
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(40);
        }
    }

    @Test
    void blockShouldHandleCjkContent() {
        var lines = Message.block(Message.Role.USER, "你好世界", 20);
        assertThat(lines).isNotEmpty();
        for (var line : lines) {
            assertThat(line.columnLength()).isEqualTo(20);
        }
    }

    @Test
    void blockShouldHandleEmptyContent() {
        var lines = Message.block(Message.Role.AGENT, "", 40);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().columnLength()).isEqualTo(40);
    }
}
