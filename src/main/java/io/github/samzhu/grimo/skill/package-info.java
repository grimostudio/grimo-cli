@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "shared", "shared::event", "shared::config", "shared::workspace", "agent::tier", "agent::registry" }
)
package io.github.samzhu.grimo.skill;
