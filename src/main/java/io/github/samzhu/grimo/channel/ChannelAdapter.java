package io.github.samzhu.grimo.channel;

/**
 * Pluggable channel adapter interface for external communication channels (e.g., Telegram, LINE).
 * Each adapter represents a single channel type and handles outbound message delivery.
 * Adapters are managed as plain Java objects at runtime (not Spring beans),
 * enabling dynamic add/remove via CLI commands through {@link ChannelRegistry}.
 */
public interface ChannelAdapter {
    String channelType();
    void send(OutgoingMessage msg);
    boolean isEnabled();
}
