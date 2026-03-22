package io.github.samzhu.grimo.skill.registry;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for skill definitions, backed by ConcurrentHashMap
 * to support runtime dynamic add/remove without Spring DI container management.
 */
public class SkillRegistry {

    private final ConcurrentHashMap<String, SkillDefinition> skills = new ConcurrentHashMap<>();

    public void register(SkillDefinition skill) {
        skills.put(skill.name(), skill);
    }

    public void remove(String name) {
        skills.remove(name);
    }

    public Optional<SkillDefinition> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public List<SkillDefinition> listAll() {
        return List.copyOf(skills.values());
    }
}
