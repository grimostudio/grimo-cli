package io.github.samzhu.grimo.channel;

import java.util.List;
import io.github.samzhu.grimo.shared.event.Attachment;

/**
 * Represents a message to be sent through a {@link ChannelAdapter}.
 *
 * @param conversationId the target conversation/chat identifier
 * @param text the message text content
 * @param attachments optional list of file/media attachments
 */
public record OutgoingMessage(
    String conversationId,
    String text,
    List<Attachment> attachments
) {}
