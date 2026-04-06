package io.github.samzhu.grimo.shared.event;

/** 😱 失敗。 */
public record DispatchFailedEvent(String agentId, String model, String errorMessage, long durationMs) implements DispatchLifecycleEvent {}
