package io.github.samzhu.grimo.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "grimo")
public record GrimoProperties(
    String workspace
) {}
