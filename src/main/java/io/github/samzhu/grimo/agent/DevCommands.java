package io.github.samzhu.grimo.agent;

import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

/**
 * /dev 指令：進入 Dev Mode（worktree + 全開權限）。
 *
 * 設計說明：
 * - /dev <goal> → 建 worktree + dispatch agent（DEV mode）
 * - 不帶 goal → 顯示用法
 * - 實際執行委託給 DevModeRunner（在 virtual thread 中）
 * - 完成後 TUI 收到 DevModeCompletedEvent 顯示 diff + merge options
 */
@Component
public class DevCommands {

    private final DevModeRunner devModeRunner;

    public DevCommands(DevModeRunner devModeRunner) {
        this.devModeRunner = devModeRunner;
    }

    @Command(name = "dev", description = "Enter Dev Mode (worktree + full access)")
    public String dev(String input) {
        if (input == null || input.isBlank()) {
            return "Usage: /dev <goal>\nExample: /dev fix the auth bug in LoginService";
        }

        var projectDir = java.nio.file.Path.of(System.getProperty("user.dir"));
        Thread.startVirtualThread(() -> devModeRunner.run(input.trim(), projectDir));

        return "\u26a1 Entering Dev Mode...";
    }
}
