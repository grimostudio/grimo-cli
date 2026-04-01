@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared", "shared::event", "shared::session", "shared::sandbox",
        "tui::widget",
        "config", "mcp", "skill::registry", "skill::loader"
    }
)
package io.github.samzhu.grimo.agent;
