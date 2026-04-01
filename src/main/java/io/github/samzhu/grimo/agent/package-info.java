@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared", "shared::event", "shared::config", "shared::workspace",
        "shared::session", "shared::tui", "shared::sandbox",
        "mcp", "skill::registry"
    }
)
package io.github.samzhu.grimo.agent;
