package io.github.samzhu.grimo.agent.tier;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.config.GrimoConfig;
import io.github.samzhu.grimo.config.GrimoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agents.model.AgentModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 整合測試：真實 config.yaml → GrimoConfig → TierRouter。
 *
 * 設計說明：
 * - 使用真實 YAML 檔案和真實 GrimoConfig（不 mock）
 * - 只 mock AgentModel（因為沒有真實 CLI agent 可用）
 * - 測試完整流程：config 讀取 → 優先順序解析 → fallback → TierSelection
 */
class TierIntegrationTest {

    @TempDir
    Path tempDir;

    private GrimoConfig config;
    private GrimoProperties grimoProperties;
    private AgentModelRegistry registry;
    private TierRouter router;
    private AtomicReference<Tier> sessionTier;

    @BeforeEach
    void setUp() throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            agents:
              default: claude
              model: claude-sonnet-4
            """);

        config = new GrimoConfig(configFile);
        grimoProperties = mock(GrimoProperties.class);
        when(grimoProperties.getTierModels()).thenReturn(Map.of(
                "lite", List.of(new GrimoProperties.TierEntry("gemini", "gemini-2.5-flash"),
                        new GrimoProperties.TierEntry("claude", "claude-haiku-4")),
                "std", List.of(new GrimoProperties.TierEntry("claude", "claude-sonnet-4"),
                        new GrimoProperties.TierEntry("gemini", "gemini-2.5-pro")),
                "pro", List.of(new GrimoProperties.TierEntry("claude", "claude-opus-4"),
                        new GrimoProperties.TierEntry("gemini", "gemini-2.5-pro"))
        ));
        when(grimoProperties.getDefaults()).thenReturn(Map.of(
                "claude", "claude-sonnet-4-6",
                "gemini", "gemini-2.5-pro"
        ));
        registry = new AgentModelRegistry();
        sessionTier = new AtomicReference<>(null);

        router = new TierRouter(registry, config, grimoProperties);
    }

    private void registerAgent(String id) {
        var model = mock(AgentModel.class);
        when(model.isAvailable()).thenReturn(true);
        registry.register(id, model);
    }

    // === 完整流程測試 ===

    private TierSelection resolveForInput() {
        var ctx = TierRouter.Context.builder()
                .sessionTier(sessionTier.get())
                .build();
        return router.resolve(ctx);
    }

    // --- 場景 1：一般對話，預設用 user-default ---

    @Test
    void normalConversationUsesUserDefault() {
        registerAgent("claude");
        var selection = resolveForInput();
        assertThat(selection.agentId()).isEqualTo("claude");
        assertThat(selection.source()).isEqualTo("user-default");
    }

    // --- 場景 2：Session tier 持續影響所有後續請求 ---

    @Test
    void sessionTierPersistsAcrossRequests() {
        registerAgent("claude");

        // 模擬 /tier pro 指令
        sessionTier.set(Tier.PRO);

        var selection1 = resolveForInput();
        assertThat(selection1.tier()).isEqualTo(Tier.PRO);
        assertThat(selection1.model()).isEqualTo("claude-opus-4");

        // /tier lite 切換
        sessionTier.set(Tier.LITE);
        registerAgent("gemini");
        var selection2 = resolveForInput();
        assertThat(selection2.tier()).isEqualTo(Tier.LITE);
    }

    // --- 場景 3：Fallback — 首選 agent 不可用，自動嘗試下一個 ---

    @Test
    void fallbackToNextAgentWhenPreferredUnavailable() {
        // pro tier 首選 claude，但只有 gemini 可用
        registerAgent("gemini");
        sessionTier.set(Tier.PRO);

        var selection = resolveForInput();
        assertThat(selection.tier()).isEqualTo(Tier.PRO);
        assertThat(selection.agentId()).isEqualTo("gemini");
        assertThat(selection.model()).isEqualTo("gemini-2.5-pro");
    }

    // --- 場景 4：所有 agent 都不可用 → 報錯 ---

    @Test
    void throwsWhenNoAgentAvailableForTier() {
        sessionTier.set(Tier.PRO);
        // 不註冊任何 agent
        assertThatThrownBy(this::resolveForInput)
                .isInstanceOf(IllegalStateException.class);
    }

    // --- 場景 5：TierOptionsFactory 產出正確的 per-request options ---

    @Test
    void tierOptionsFactoryProducesCorrectOptionsForSelection() {
        registerAgent("claude");
        sessionTier.set(Tier.PRO);
        var selection = resolveForInput();
        var factory = new TierOptionsFactory();
        var options = factory.build(selection.agentId(), selection.model());

        assertThat(options.getModel()).isEqualTo("claude-opus-4");
    }

    // --- 場景 6：config 未設定 default agent 時 fallback 到 tier-models ---

    @Test
    void fallsBackToTierModelsWhenNoDefaultAgent() throws IOException {
        var configFile = tempDir.resolve("minimal-config.yaml");
        Files.writeString(configFile, """
            agents:
              model: claude-sonnet-4
            """);
        var minimalConfig = new GrimoConfig(configFile);
        var minimalRouter = new TierRouter(registry, minimalConfig, grimoProperties);

        registerAgent("claude");

        var ctx = TierRouter.Context.builder().build();
        var selection = minimalRouter.resolve(ctx);

        assertThat(selection.agentId()).isEqualTo("claude");
    }
}
