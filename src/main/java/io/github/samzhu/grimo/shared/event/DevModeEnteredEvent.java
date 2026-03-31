package io.github.samzhu.grimo.shared.event;

/**
 * Dev Mode 進入時發布。
 * TUI 收到後顯示 worktree 資訊。
 */
public record DevModeEnteredEvent(String branchName, String workDir) {}
