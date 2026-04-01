package io.github.samzhu.grimo.shared.event;

/**
 * Agent 對話記錄事件。
 *
 * 設計說明：解耦 GrimoSessionAdvisor → SessionWriter 直接依賴。
 * Advisor 發 event，SessionEventListener 監聽後寫入 JSONL。
 */
public record AgentCallRecordedEvent(String userGoal, String agentResult) {}
