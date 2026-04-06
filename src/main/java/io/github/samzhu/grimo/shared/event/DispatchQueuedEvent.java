package io.github.samzhu.grimo.shared.event;

/** 👀 系統收到訊息，開始處理。 */
public record DispatchQueuedEvent(String userInput) implements DispatchLifecycleEvent {}
