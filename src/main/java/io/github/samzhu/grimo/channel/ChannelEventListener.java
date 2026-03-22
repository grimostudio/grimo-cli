package io.github.samzhu.grimo.channel;

import io.github.samzhu.grimo.shared.event.OutgoingMessageEvent;
import org.springframework.modulith.events.ApplicationModuleListener;

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
     */
    @ApplicationModuleListener
    public void onOutgoingMessage(OutgoingMessageEvent event) {
        registry.get(event.channelType()).ifPresent(adapter -> {
            adapter.send(new OutgoingMessage(
                event.conversationId(),
                event.text(),
                event.attachments()
            ));
        });
    }
}
