package io.github.samzhu.grimo.agent.router;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.model.AgentModel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRouterTest {

    private AgentModelRegistry registry;
    private GrimoConfig config;
    private AgentRouter router;

    @BeforeEach
    void setUp() {
        registry = new AgentModelRegistry();
        config = mock(GrimoConfig.class);
        router = new AgentRouter(registry, config);
    }

    @Test
    void routeByExplicitId() {
        var model = mock(AgentModel.class);
        when(model.isAvailable()).thenReturn(true);
        registry.register("claude", model);
        assertThat(router.route("claude")).isSameAs(model);
    }

    @Test
    void routeByExplicitIdThrowsWhenNotFound() {
        assertThatThrownBy(() -> router.route("nonexistent"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void routeUsesConfigDefault() {
        var model = mock(AgentModel.class);
        when(model.isAvailable()).thenReturn(true);
        registry.register("gemini", model);
        when(config.getDefaultAgent()).thenReturn("gemini");
        assertThat(router.route(null)).isSameAs(model);
    }

    @Test
    void routeFallsBackToFirstAvailable() {
        var model = mock(AgentModel.class);
        when(model.isAvailable()).thenReturn(true);
        registry.register("claude", model);
        when(config.getDefaultAgent()).thenReturn(null);
        assertThat(router.route(null)).isSameAs(model);
    }

    @Test
    void routeThrowsWhenNoAgentsAvailable() {
        when(config.getDefaultAgent()).thenReturn(null);
        assertThatThrownBy(() -> router.route(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
