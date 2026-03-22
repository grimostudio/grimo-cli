package io.github.samzhu.grimo.shared.event;

public record ScheduleTaskEvent(
    String taskId,
    String cron,
    String description
) {}
