package io.github.samzhu.grimo.shared.event;

/** 🤔 Agent 開始思考。 */
public record DispatchThinkingStartedEvent(String agentId, String model) implements DispatchLifecycleEvent {}
