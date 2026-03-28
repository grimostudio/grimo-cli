package io.github.samzhu.grimo.skill.loader;

import java.util.List;
import java.util.Map;

/**
 * Skill 定義資料模型，對齊 Agent Skills 開放標準（agentskills.io/specification）。
 *
 * 設計說明：
 * - 標準欄位：name, description, license, compatibility, allowedTools, metadata
 * - Claude Code 擴充欄位：model, effort, context, agent, userInvocable, disableModelInvocation, argumentHint, paths, shell
 * - Grimo 擴充放在 metadata map 裡（grimo.tier, grimo.subagents, grimo.execution 等）
 * - 第三方 Skill（為 Claude Code / Gemini CLI 撰寫的）安裝到 Grimo 不會解析失敗
 *
 * @see <a href="https://agentskills.io/specification">Agent Skills Specification</a>
 * @see <a href="https://code.claude.com/docs/en/skills">Claude Code Skills Documentation</a>
 */
public record SkillDefinition(
    // Agent Skills 標準欄位
    String name,
    String description,
    String license,
    String compatibility,
    List<String> allowedTools,
    Map<String, String> metadata,
    // Claude Code 擴充欄位
    String model,
    String effort,
    String context,
    String agent,
    Boolean userInvocable,
    Boolean disableModelInvocation,
    String argumentHint,
    List<String> paths,
    String shell,
    // Body（Markdown 內容）
    String body
) {
    /**
     * Grimo 擴充：建議的執行等級（lite/std/pro）。
     * 預設 "std"。用於 F3 Tier 系統路由。
     */
    public String grimoTier() {
        return metadata().getOrDefault("grimo.tier", "std");
    }

    /**
     * Grimo 擴充：調度方式（parallel/sequential）。
     * 空字串表示單一 agent 執行。用於 F4 Sub-Agent 調度。
     */
    public String grimoExecution() {
        return metadata().getOrDefault("grimo.execution", "");
    }

    /**
     * Grimo 擴充：作者。從 metadata 讀取，向後相容舊格式。
     */
    public String grimoAuthor() {
        return metadata().getOrDefault("grimo.author", "");
    }

    /**
     * Grimo 擴充：版本。從 metadata 讀取，向後相容舊格式。
     */
    public String grimoVersion() {
        return metadata().getOrDefault("grimo.version", "");
    }

    /**
     * Grimo 擴充：sub-agent 列表（JSON 字串）。
     * 用於 F4 Sub-Agent 調度。回傳原始 JSON 字串，由調度層解析。
     */
    public String grimoSubagents() {
        return metadata().getOrDefault("grimo.subagents", "");
    }
}
