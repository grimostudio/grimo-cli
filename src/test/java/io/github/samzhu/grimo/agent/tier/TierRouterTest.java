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

        when(config.getTierModels()).thenReturn(Map.of(
                "lite", List.of(
                        Map.of("agent", "gemini", "model", "gemini-2.5-flash"),
                        Map.of("agent", "claude", "model", "claude-haiku-4")),
                "std", List.of(
                        Map.of("agent", "claude", "model", "claude-sonnet-4")),
                "pro", List.of(
                        Map.of("agent", "claude", "model", "claude-opus-4"),
                        Map.of("agent", "gemini", "model", "gemini-2.5-pro"))
        ));
        when(config.getSkillOverrides()).thenReturn(Map.of());
        when(config.getTierKeywords()).thenReturn(Map.of());
        when(grimoProperties.getTierModels()).thenReturn(Map.of());

        router = new TierRouter(registry, config, grimoProperties);
    }

    private void registerAgent(String id) {
        var model = mock(AgentModel.class);
        when(model.isAvailable()).thenReturn(true);
        registry.register(id, model);
    }

    @Test
    void resolveUsesSkillMetadataTier() {
        registerAgent("claude");
        var ctx = TierRouter.Context.builder().skillTier("pro").build();
        var selection = router.resolve(ctx);
        assertThat(selection.tier()).isEqualTo(Tier.PRO);
        assertThat(selection.agentId()).isEqualTo("claude");
        assertThat(selection.model()).isEqualTo("claude-opus-4");
        assertThat(selection.source()).isEqualTo("skill-metadata");
    }

    @Test
    void resolveDefaultsToStd() {
        registerAgent("claude");
        var ctx = TierRouter.Context.builder().build();
        var selection = router.resolve(ctx);
        assertThat(selection.tier()).isEqualTo(Tier.STD);
        assertThat(selection.source()).isEqualTo("default");
    }

    @Test
    void resolveSessionTierOverridesSkillMetadata() {
        registerAgent("claude");
        var ctx = TierRouter.Context.builder().sessionTier(Tier.PRO).skillTier("std").build();
        var selection = router.resolve(ctx);
        assertThat(selection.tier()).isEqualTo(Tier.PRO);
        assertThat(selection.source()).isEqualTo("session");
    }

    @Test
    void resolveKeywordOverridesAll() {
        registerAgent("claude");
        var ctx = TierRouter.Context.builder().keywordTier(Tier.PRO).sessionTier(Tier.LITE).skillTier("std").build();
        var selection = router.resolve(ctx);
        assertThat(selection.tier()).isEqualTo(Tier.PRO);
        assertThat(selection.source()).isEqualTo("keyword");
    }

    @Test
    void resolveSkillOverrideTierWalksFallbackList() {
        registerAgent("gemini");
        when(config.getSkillOverrides()).thenReturn(Map.of("deep-research", Map.of("tier", "pro")));
        var ctx = TierRouter.Context.builder().skillName("deep-research").build();
        var selection = router.resolve(ctx);
        assertThat(selection.tier()).isEqualTo(Tier.PRO);
        assertThat(selection.agentId()).isEqualTo("gemini");
        assertThat(selection.model()).isEqualTo("gemini-2.5-pro");
        assertThat(selection.source()).isEqualTo("skill-override");
    }

    @Test
    void resolveSkillOverrideDirectAgentBypassesTierList() {
        registerAgent("claude");
        when(config.getSkillOverrides()).thenReturn(Map.of("tdd-workflow", Map.of("agent", "claude", "model", "claude-opus-4")));
        var ctx = TierRouter.Context.builder().skillName("tdd-workflow").build();
        var selection = router.resolve(ctx);
        assertThat(selection.agentId()).isEqualTo("claude");
        assertThat(selection.model()).isEqualTo("claude-opus-4");
        assertThat(selection.source()).isEqualTo("skill-override-direct");
    }

    @Test
    void resolveFallbackSkipsUnavailableAgent() {
        registerAgent("gemini");
        var ctx = TierRouter.Context.builder().skillTier("pro").build();
        var selection = router.resolve(ctx);
        assertThat(selection.agentId()).isEqualTo("gemini");
        assertThat(selection.model()).isEqualTo("gemini-2.5-pro");
    }

    @Test
    void resolveThrowsWhenNoAgentAvailableForTier() {
        var ctx = TierRouter.Context.builder().skillTier("pro").build();
        assertThatThrownBy(() -> router.resolve(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pro");
    }

    @Test
    void resolveHandlesEmptyTierConfig() {
        registerAgent("claude");
        when(config.getTierModels()).thenReturn(Map.of());
        when(config.getDefaultAgent()).thenReturn("claude");
        when(config.getAgentOption("claude", "model")).thenReturn("claude-sonnet-4");

        router = new TierRouter(registry, config, grimoProperties);
        var ctx = TierRouter.Context.builder().build();
        var selection = router.resolve(ctx);
        assertThat(selection.agentId()).isEqualTo("claude");
    }

    @Test
    void resolveFallsBackToGrimoPropertiesWhenConfigTierEmpty() {
        registerAgent("claude");
        when(config.getTierModels()).thenReturn(Map.of(
                "std", List.of(Map.of("agent", "claude", "model", "claude-sonnet-4"))
        ));
        when(grimoProperties.getTierModels()).thenReturn(Map.of(
                "pro", List.of(new GrimoProperties.TierEntry("claude", "claude-opus-4"))
        ));
        router = new TierRouter(registry, config, grimoProperties);

        var ctx = TierRouter.Context.builder().skillTier("pro").build();
        var selection = router.resolve(ctx);
        assertThat(selection.agentId()).isEqualTo("claude");
        assertThat(selection.model()).isEqualTo("claude-opus-4");
    }
}
