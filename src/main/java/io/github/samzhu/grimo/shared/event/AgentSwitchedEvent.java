package io.github.samzhu.grimo.shared.event;

/**
 * Agent 或 model 切換時發布。
 * 由 AgentCommands.use() 在 config 寫入後 publish。
 * TUI 層 listen 後自動刷新 status bar。
 *
 * 設計說明：
 * - 遵循 CLAUDE.md 架構須知：Command → Event → TUI
 * - 使用 Spring @EventListener（不是 @ApplicationModuleListener，CLI 無 DB）
 */
public record AgentSwitchedEvent(String agentId, String model) {}
