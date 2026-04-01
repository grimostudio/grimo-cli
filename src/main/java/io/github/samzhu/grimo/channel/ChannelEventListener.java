package io.github.samzhu.grimo.channel;

import io.github.samzhu.grimo.shared.event.OutgoingMessageEvent;
import org.springframework.context.event.EventListener;

/**
 * Listens for {@link OutgoingMessageEvent} domain events and routes them
 * to the appropriate {@link ChannelAdapter} via the {@link ChannelRegistry}.
 *
 * This decouples message producers (agent, task scheduler) from channel
 * adapters, allowing any module to send outbound messages by simply
 * publishing an {@link OutgoingMessageEvent} without knowing which
 * channel adapter will handle it.
 */
public class ChannelEventListener {

    private final ChannelRegistry registry;

    public ChannelEventListener(ChannelRegistry registry) {
        this.registry = registry;
    }

    /**
     * Handles an outgoing message event by looking up the target channel adapter
     * and forwarding the message. If no adapter is registered for the channel type,
     * the event is silently ignored.
     *
     * 設計說明：
     * - targetAdapter 優先：若 event.targetAdapter() 不為 null 且不是 "tui"，以此為路由 key
     * - 否則 fallback 到 channelType（舊行為，向下相容 Channel adapter 發出的 OutgoingMessageEvent）
     * - targetAdapter == "tui" 或 null（broadcast）的訊息由 TuiAdapter 的 @EventListener 處理，
     *   ChannelEventListener 不處理（避免重複路由到不存在的 "tui" channel adapter）
     */
    @EventListener
    public void onOutgoingMessage(OutgoingMessageEvent event) {
        String target = event.targetAdapter();
        // "tui" target 由 TuiAdapter 處理，不走 ChannelRegistry
        if ("tui".equals(target)) {
            return;
        }
        // 優先用 targetAdapter 路由，否則 fallback 到 channelType
        String routeKey = (target != null) ? target : event.channelType();
        registry.get(routeKey).ifPresent(adapter -> {
            adapter.send(new OutgoingMessage(
                event.conversationId(),
                event.text(),
                event.attachments()
            ));
        });
    }
}
