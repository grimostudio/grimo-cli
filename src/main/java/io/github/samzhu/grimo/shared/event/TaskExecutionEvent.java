package io.github.samzhu.grimo.shared.event;

import java.time.Instant;

public record TaskExecutionEvent(
    String taskId,
    String description,
    Instant triggeredAt
) {}
