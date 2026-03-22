package io.github.samzhu.grimo.agent.router;

import io.github.samzhu.grimo.agent.provider.*;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRouterTest {

    @TempDir
    Path tempDir;

    AgentProviderRegistry registry;
    AgentRouter router;

    @BeforeEach
    void setUp() {
        registry = new AgentProviderRegistry();
        router = new AgentRouter(registry);
    }

    @Test
    void routeByExplicitIdShouldReturnSpecifiedProvider() {
        registry.register("anthropic", stubProvider("anthropic", AgentType.API, true));
        registry.register("ollama", stubProvider("ollama", AgentType.API, true));

        var provider = router.route("anthropic");

        assertThat(provider.id()).isEqualTo("anthropic");
    }

    @Test
    void routeByExplicitIdShouldThrowWhenNotAvailable() {
        registry.register("offline", stubProvider("offline", AgentType.API, false));

        assertThatThrownBy(() -> router.route("offline"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void autoRouteShouldPreferCliOverApi() {
        registry.register("anthropic-api", stubProvider("anthropic-api", AgentType.API, true));
        registry.register("claude-cli", stubProvider("claude-cli", AgentType.CLI, true));

        var provider = router.route(null);

        assertThat(provider.type()).isEqualTo(AgentType.CLI);
    }

    @Test
    void autoRouteShouldFallbackToApiWhenNoCliAvailable() {
        registry.register("anthropic-api", stubProvider("anthropic-api", AgentType.API, true));

        var provider = router.route(null);

        assertThat(provider.type()).isEqualTo(AgentType.API);
    }

    @Test
    void autoRouteShouldThrowWhenNoProvidersAvailable() {
        assertThatThrownBy(() -> router.route(null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void autoRouteShouldPreferConfigDefault() {
        registry.register("anthropic-api", stubProvider("anthropic-api", AgentType.API, true));
        registry.register("claude-cli", stubProvider("claude-cli", AgentType.CLI, true));

        var configFile = tempDir.resolve("config.yaml");
        try {
            java.nio.file.Files.writeString(configFile, "agents:\n  default: anthropic-api\n");
        } catch (Exception e) { throw new RuntimeException(e); }
        var config = new GrimoConfig(configFile);
        var routerWithConfig = new AgentRouter(registry, config);

        var provider = routerWithConfig.route(null);

        assertThat(provider.id()).isEqualTo("anthropic-api");
    }

    @Test
    void autoRouteShouldFallbackWhenConfigAgentUnavailable() {
        registry.register("claude-cli", stubProvider("claude-cli", AgentType.CLI, true));

        var configFile = tempDir.resolve("config.yaml");
        try {
            java.nio.file.Files.writeString(configFile, "agents:\n  default: nonexistent\n");
        } catch (Exception e) { throw new RuntimeException(e); }
        var config = new GrimoConfig(configFile);
        var routerWithConfig = new AgentRouter(registry, config);

        var provider = routerWithConfig.route(null);

        assertThat(provider.id()).isEqualTo("claude-cli");
    }

    private AgentProvider stubProvider(String id, AgentType type, boolean available) {
        return new AgentProvider() {
            @Override public String id() { return id; }
            @Override public AgentType type() { return type; }
            @Override public boolean isAvailable() { return available; }
            @Override public AgentResult execute(AgentRequest request) {
                return new AgentResult(true, "stub");
            }
        };
    }
}
