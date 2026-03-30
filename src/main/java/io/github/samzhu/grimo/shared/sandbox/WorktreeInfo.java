package io.github.samzhu.grimo.shared.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * Agent 派遣的工作區資訊。
 *
 * 設計說明：
 * - worktree 模式：agent 在獨立 git worktree 工作，完成後使用者決定是否 merge
 * - fallback 模式：非 git 目錄，直接使用 CWD + symlink skills（現有行為）
 * - baseSha 用於 diff 比較，避免依賴分支名稱
 *
 * @param workDir agent 的工作目錄（worktree path 或 fallback CWD）
 * @param branchName worktree 分支名稱（fallback 時為 null）
 * @param baseSha worktree 建立時的 HEAD SHA（用於 diff 比較，fallback 時為 null）
 * @param provisionedSkills 已配置的 skill 名稱
 * @param isWorktree true=git worktree 模式, false=fallback CWD
 */
public record WorktreeInfo(
    Path workDir,
    String branchName,
    String baseSha,
    List<String> provisionedSkills,
    boolean isWorktree
) {}
