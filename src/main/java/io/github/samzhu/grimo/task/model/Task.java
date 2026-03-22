package io.github.samzhu.grimo.task.model;

import java.time.Instant;

public record Task(
    String id,
    TaskType type,
    TaskStatus status,
    String description,
    String cron,           // null for non-cron
    Long delaySeconds,     // null for non-delayed
    String channel,        // source channel
    Instant created,
    Instant lastRun,
    Instant nextRun,
    String body            // markdown body (instructions + execution log)
) {
    public Task withStatus(TaskStatus newStatus) {
        return new Task(id, type, newStatus, description, cron, delaySeconds,
            channel, created, lastRun, nextRun, body);
    }

    public Task withLastRun(Instant run) {
        return new Task(id, type, status, description, cron, delaySeconds,
            channel, created, run, nextRun, body);
    }

    public Task withNextRun(Instant next) {
        return new Task(id, type, status, description, cron, delaySeconds,
            channel, created, lastRun, next, body);
    }

    public Task withBody(String newBody) {
        return new Task(id, type, status, description, cron, delaySeconds,
            channel, created, lastRun, nextRun, newBody);
    }
}
