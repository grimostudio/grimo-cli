package io.github.samzhu.grimo.shared.event;

/**
 * ⚡ Agent 搜尋/瀏覽網頁。
 *
 * 未來由 Agent Client SDK streaming callback 發布。目前只定義，不發布。
 */
public record DispatchWebSearchEvent(String agentId, String query) implements DispatchLifecycleEvent {}
