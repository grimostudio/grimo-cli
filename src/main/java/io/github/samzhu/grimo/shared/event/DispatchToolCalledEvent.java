package io.github.samzhu.grimo.shared.event;

/**
 * 🔥 Agent 呼叫工具。
 *
 * 未來由 Agent Client SDK streaming callback 發布。目前只定義，不發布。
 */
public record DispatchToolCalledEvent(String agentId, String toolName) implements DispatchLifecycleEvent {}
