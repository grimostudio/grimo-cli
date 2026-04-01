@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared", "shared::event", "shared::session", "shared::sandbox",
        "config", "mcp", "skill::registry", "skill::loader"
    }
)
package io.github.samzhu.grimo.agent;
