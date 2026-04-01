@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared", "shared::event", "shared::session", "shared::tui", "shared::sandbox",
        "config", "mcp", "skill::registry"
    }
)
package io.github.samzhu.grimo.agent;
