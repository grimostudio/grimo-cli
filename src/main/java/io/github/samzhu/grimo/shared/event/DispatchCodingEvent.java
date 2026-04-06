package io.github.samzhu.grimo.shared.event;

/**
 * 👨‍💻 Agent 寫/編輯程式碼。
 *
 * 未來由 Agent Client SDK streaming callback 發布。目前只定義，不發布。
 */
public record DispatchCodingEvent(String agentId, String filePath) implements DispatchLifecycleEvent {}
