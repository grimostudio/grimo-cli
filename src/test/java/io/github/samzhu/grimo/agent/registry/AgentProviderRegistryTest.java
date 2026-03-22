package io.github.samzhu.grimo.agent.registry;

import io.github.samzhu.grimo.agent.provider.AgentProvider;
import io.github.samzhu.grimo.agent.provider.AgentRequest;
import io.github.samzhu.grimo.agent.provider.AgentResult;
import io.github.samzhu.grimo.agent.provider.AgentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProviderRegistryTest {

    AgentProviderRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentProviderRegistry();
    }

    @Test
    void registerAndRetrieveProvider() {
        var provider = stubProvider("test-agent", AgentType.API, true);
        registry.register("test-agent", provider);

        assertThat(registry.get("test-agent")).isPresent();
        assertThat(registry.get("test-agent").get().id()).isEqualTo("test-agent");
    }

    @Test
    void getReturnsEmptyForUnknownId() {
        assertThat(registry.get("nonexistent")).isEmpty();
    }

    @Test
    void removeProvider() {
        var provider = stubProvider("to-remove", AgentType.API, true);
        registry.register("to-remove", provider);
        registry.remove("to-remove");

        assertThat(registry.get("to-remove")).isEmpty();
    }

    @Test
    void listAllProviders() {
        registry.register("a", stubProvider("a", AgentType.API, true));
        registry.register("b", stubProvider("b", AgentType.CLI, true));

        assertThat(registry.listAll()).hasSize(2);
    }

    @Test
    void listAvailableProviders() {
        registry.register("available", stubProvider("available", AgentType.API, true));
        registry.register("unavailable", stubProvider("unavailable", AgentType.API, false));

        assertThat(registry.listAvailable()).hasSize(1);
        assertThat(registry.listAvailable().getFirst().id()).isEqualTo("available");
    }

    private AgentProvider stubProvider(String id, AgentType type, boolean available) {
        return new AgentProvider() {
            @Override public String id() { return id; }
            @Override public AgentType type() { return type; }
            @Override public boolean isAvailable() { return available; }
            @Override public AgentResult execute(AgentRequest request) {
                return new AgentResult(true, "stub response");
            }
        };
    }
}
