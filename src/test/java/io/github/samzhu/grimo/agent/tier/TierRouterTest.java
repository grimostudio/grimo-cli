package io.github.samzhu.grimo.agent.tier;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.config.GrimoConfig;
import io.github.samzhu.grimo.config.GrimoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.model.AgentModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TierRouterTest {

    private AgentModelRegistry registry;
    private GrimoConfig config;
    private GrimoProperties grimoProperties;
    private TierRouter router;

    @BeforeEach
    void setUp() {
        registry = new AgentModelRegistry();
        config = mock(GrimoConfig.class);
        grimoProperties = mock(GrimoProperties.class);

        when(grimoProperties.getTierModels()).thenReturn(Map.of(
                "lite", List.of(
                        new GrimoProperties.TierEntry("gemini", "gemini-2.5-flash"),
                        new GrimoProperties.TierEntry("claude", "claude-haiku-4")),
                "std", List.of(
                        new GrimoProperties.TierEntry("claude", "claude-sonnet-4")),
                "pro", List.of(
                        new GrimoProperties.TierEntry("claude", "claude-opus-4"),
                        new GrimoProperties.TierEntry("gemini", "gemini-2.5-pro"))
        ));
        when(grimoProperties.getDefaults()).thenReturn(Map.of(
                "claude", "claude-sonnet-4-6",
                "gemini", "gemini-2.5-pro"
        ));

        router = new TierRouter(registry, config, grimoProperties);
    }

    private void registerAgent(String id) {
        var model = mock(AgentModel.class);
        when(model.isAvailable()).thenReturn(true);
        registry.register(id, model);
    }

    @Test
    void resolveDefaultsToStdWhenNoDefaultAgent() {
        registerAgent("claude");
        when(config.getDefaultAgent()).thenReturn(null);
        var ctx = TierRouter.Context.builder().build();
        var selection = router.resolve(ctx);
        assertThat(selection.tier()).isEqualTo(Tier.STD);
        assertThat(selection.source()).isEqualTo("default");
    }

    @Test
    void resolveUsesUserDefaultWhenSet() {
        registerAgent("claude");
        when(config.getDefaultAgent()).thenReturn("claude");
        when(config.getDefaultModel()).thenReturn("claude-sonnet-4");
        var ctx = TierRouter.Context.builder().build();
        var selection = router.resolve(ctx);
        assertThat(selection.agentId()).isEqualTo("claude");
        assertThat(selection.model()).isEqualTo("claude-sonnet-4");
        assertThat(selection.source()).isEqualTo("user-default");
    }

    @Test
    void resolveSessionTierWalksFallbackList() {
        registerAgent("claude");
        var ctx = TierRouter.Context.builder().sessionTier(Tier.PRO).build();
        var selection = router.resolve(ctx);
        assertThat(selection.tier()).isEqualTo(Tier.PRO);
        assertThat(selection.agentId()).isEqualTo("claude");
        assertThat(selection.model()).isEqualTo("claude-opus-4");
        assertThat(selection.source()).isEqualTo("session");
    }

    @Test
    void resolveFallbackSkipsUnavailableAgent() {
        registerAgent("gemini");
        var ctx = TierRouter.Context.builder().sessionTier(Tier.PRO).build();
        var selection = router.resolve(ctx);
        assertThat(selection.agentId()).isEqualTo("gemini");
        assertThat(selection.model()).isEqualTo("gemini-2.5-pro");
    }

    @Test
    void resolveThrowsWhenDefaultAgentUnavailable() {
        when(config.getDefaultAgent()).thenReturn("claude");
        // claude not registered
        var ctx = TierRouter.Context.builder().build();
        assertThatThrownBy(() -> router.resolve(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claude");
    }

    @Test
    void resolveThrowsWhenNoAgentAvailableForTier() {
        // No agents registered, no default agent
        when(config.getDefaultAgent()).thenReturn(null);
        when(grimoProperties.getTierModels()).thenReturn(Map.of(
                "std", List.of(new GrimoProperties.TierEntry("claude", "claude-sonnet-4"))
        ));
        router = new TierRouter(registry, config, grimoProperties);

        var ctx = TierRouter.Context.builder().build();
        assertThatThrownBy(() -> router.resolve(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No agents available");
    }

    @Test
    void resolveDefaultWithConfigAndAvailableAgent() {
        registerAgent("claude");
        when(config.getDefaultAgent()).thenReturn("claude");
        when(config.getDefaultModel()).thenReturn("claude-sonnet-4");

        var selection = router.resolveDefault();
        assertThat(selection.agentId()).isEqualTo("claude");
        assertThat(selection.model()).isEqualTo("claude-sonnet-4");
        assertThat(selection.source()).isEqualTo("user-default");
    }

    @Test
    void resolveDefaultWithConfigAndUnavailableAgentShouldThrow() {
        when(config.getDefaultAgent()).thenReturn("claude");

        assertThatThrownBy(() -> router.resolveDefault())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claude");
    }

    @Test
    void resolveDefaultWithoutConfigWalksTierFallback() {
        registerAgent("gemini");
        when(config.getDefaultAgent()).thenReturn(null);

        var selection = router.resolveDefault();
        assertThat(selection.agentId()).isEqualTo("gemini");
    }

    @Test
    void resolveUsesGrimoPropertiesDefaultModelWhenConfigModelNull() {
        registerAgent("claude");
        when(config.getDefaultAgent()).thenReturn("claude");
        when(config.getDefaultModel()).thenReturn(null);

        var ctx = TierRouter.Context.builder().build();
        var selection = router.resolve(ctx);
        assertThat(selection.model()).isEqualTo("claude-sonnet-4-6"); // from grimoProperties.getDefaults()
    }
}
