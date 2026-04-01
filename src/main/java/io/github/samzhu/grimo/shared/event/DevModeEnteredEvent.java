package io.github.samzhu.grimo.shared.event;

/**
 * Dev Mode 進入時發布。
 * TUI 收到後顯示 worktree 資訊。
 * SessionEventListener 收到後寫入 dispatch-entered 到 session。
 */
public record DevModeEnteredEvent(
    String taskId,
    String agent,
    String model,
    String tier,
    String goal,
    String branchName,
    String workDir
) {}
