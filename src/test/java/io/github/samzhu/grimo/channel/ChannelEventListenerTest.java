package io.github.samzhu.grimo.channel;

import io.github.samzhu.grimo.shared.event.OutgoingMessageEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelEventListenerTest {

    @Test
    void shouldRouteMessageToCorrectChannel() {
        var sentMessages = new ArrayList<OutgoingMessage>();
        var registry = new ChannelRegistry();
        registry.register("telegram", new ChannelAdapter() {
            @Override public String channelType() { return "telegram"; }
            @Override public void send(OutgoingMessage msg) { sentMessages.add(msg); }
            @Override public boolean isEnabled() { return true; }
        });

        var listener = new ChannelEventListener(registry);
        listener.onOutgoingMessage(new OutgoingMessageEvent("telegram", "conv-1", "Hello!", List.of()));

        assertThat(sentMessages).hasSize(1);
        assertThat(sentMessages.getFirst().text()).isEqualTo("Hello!");
    }

    @Test
    void shouldIgnoreMessageForUnknownChannel() {
        var registry = new ChannelRegistry();
        var listener = new ChannelEventListener(registry);

        // Should not throw
        listener.onOutgoingMessage(new OutgoingMessageEvent("unknown", "conv-1", "Hello!", List.of()));
    }
}
