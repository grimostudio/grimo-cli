package io.github.samzhu.grimo.shared.event;

import java.time.Instant;

public record TaskCompletedEvent(
    String taskId,
    boolean success,
    String result,
    Instant completedAt
) {}
