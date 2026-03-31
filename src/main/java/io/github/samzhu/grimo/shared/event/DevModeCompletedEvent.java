package io.github.samzhu.grimo.shared.event;

/**
 * Dev Mode 完成時發布。
 * TUI 收到後顯示 diff summary + merge options。
 *
 * @param branchName worktree 分支名稱
 * @param commitCount baseSha 到分支的 commit 數量
 * @param diffStat diff 統計文字
 * @param durationMs 執行時間（毫秒）
 * @param hasChanges agent 是否實際修改了檔案
 * @param result agent 回覆文字
 */
public record DevModeCompletedEvent(
    String branchName,
    int commitCount,
    String diffStat,
    long durationMs,
    boolean hasChanges,
    String result
) {}
