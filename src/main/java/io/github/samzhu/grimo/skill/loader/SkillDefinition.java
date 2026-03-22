package io.github.samzhu.grimo.skill.loader;

import java.util.List;

public record SkillDefinition(
    String name,
    String description,
    String version,
    String author,
    String executor,
    List<String> triggers,
    String body
) {}
