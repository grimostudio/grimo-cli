package io.github.samzhu.grimo.shared.event;

import java.util.List;

public record OutgoingMessageEvent(
    String channelType,
    String conversationId,
    String text,
    List<Attachment> attachments
) {}
