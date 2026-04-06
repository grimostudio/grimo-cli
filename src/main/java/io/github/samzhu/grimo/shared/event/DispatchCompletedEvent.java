package io.github.samzhu.grimo.shared.event;

/** 👍 成功完成。 */
public record DispatchCompletedEvent(String agentId, String model, long durationMs, int resultLength) implements DispatchLifecycleEvent {}
