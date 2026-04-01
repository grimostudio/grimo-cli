package io.github.samzhu.grimo.shared.event;

/**
 * Dev Mode 完成時發布。
 */
public record DevModeCompletedEvent(
    String taskId,
    String agent,
    String model,
    String tier,
    String goal,
    String executionMode,
    String workDir,
    String branchName,
    String baseSha,
    int commitCount,
    String diffStat,
    long durationMs,
    boolean hasChanges,
    String result,
    String externalSessionPath
) {}
