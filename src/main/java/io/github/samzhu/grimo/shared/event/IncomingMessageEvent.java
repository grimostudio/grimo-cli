package io.github.samzhu.grimo.shared.event;

import java.time.Instant;
import java.util.List;

public record IncomingMessageEvent(
    String channelType,       // "telegram" | "line" | "cli"
    String channelUserId,
    String conversationId,
    String text,
    List<Attachment> attachments,
    Instant timestamp
) {}
