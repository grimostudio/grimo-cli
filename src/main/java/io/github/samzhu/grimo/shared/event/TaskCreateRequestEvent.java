package io.github.samzhu.grimo.shared.event;

public record TaskCreateRequestEvent(
    String description,
    String taskType,    // "immediate" | "delayed" | "cron"
    String cron,        // null for non-cron
    Long delaySeconds,  // null for non-delayed
    String sourceChannel,
    String conversationId
) {}
