package io.github.samzhu.grimo.shared.event;

/**
 * AI dispatch 生命週期事件群組。
 *
 * 設計說明（OpenClaw Reaction Lifecycle 模式）：
 * - sealed interface 分組：訂閱者可訂閱父類別收全部（logging），或訂閱個別 event（TUI）
 * - 每個 event 獨立 record，帶自己的 payload（DDD domain event）
 * - 反映 orchestration state，不是 message content
 * - 遵循 Spring @EventListener（非 @ApplicationModuleListener，CLI 無 transaction）
 *
 * @see <a href="https://docs.spring.io/spring-modulith/reference/events.html">Spring Modulith Events</a>
 */
public sealed interface DispatchLifecycleEvent permits
    DispatchQueuedEvent,
    DispatchThinkingStartedEvent,
    DispatchToolCalledEvent,
    DispatchCodingEvent,
    DispatchWebSearchEvent,
    DispatchResponseReceivedEvent,
    DispatchCompletedEvent,
    DispatchFailedEvent {}
