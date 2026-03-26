package io.github.samzhu.grimo.agent.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.model.AgentModel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentModelRegistryTest {

    private AgentModelRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentModelRegistry();
    }

    @Test
    void registerAndGet() {
        var model = mock(AgentModel.class);
        registry.register("claude", model);
        assertThat(registry.get("claude")).isSameAs(model);
    }

    @Test
    void getUnknownReturnsNull() {
        assertThat(registry.get("nonexistent")).isNull();
    }

    @Test
    void removeUnregisters() {
        var model = mock(AgentModel.class);
        registry.register("claude", model);
        registry.remove("claude");
        assertThat(registry.get("claude")).isNull();
    }

    @Test
    void listAllReturnsAllRegistered() {
        var m1 = mock(AgentModel.class);
        var m2 = mock(AgentModel.class);
        registry.register("claude", m1);
        registry.register("gemini", m2);
        assertThat(registry.listAll()).hasSize(2).containsKeys("claude", "gemini");
    }

    @Test
    void listAvailableFiltersUnavailable() {
        var available = mock(AgentModel.class);
        when(available.isAvailable()).thenReturn(true);
        var unavailable = mock(AgentModel.class);
        when(unavailable.isAvailable()).thenReturn(false);
        registry.register("claude", available);
        registry.register("gemini", unavailable);
        assertThat(registry.listAvailable()).hasSize(1).containsKey("claude");
    }
}
