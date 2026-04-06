package io.github.samzhu.grimo.shared.event;

/**
 * ✨ 收到回覆。
 *
 * 目前與 Completed 幾乎同時發生。未來 streaming 模式下在第一個 token 到達時發布。
 */
public record DispatchResponseReceivedEvent(String agentId, String model, long durationMs) implements DispatchLifecycleEvent {}
