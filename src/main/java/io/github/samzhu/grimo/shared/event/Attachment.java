package io.github.samzhu.grimo.shared.event;

public record Attachment(
    String type,      // "image", "file", "audio"
    String url,
    String fileName
) {}
