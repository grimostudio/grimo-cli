package io.github.samzhu.grimo.agent.tier;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.config.GrimoConfig;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 整合測試：真實 config.yaml → GrimoConfig → TierKeywordDetector → TierRouter。
 *
 * 設計說明：
 * - 使用真實 YAML 檔案和真實 GrimoConfig（不 mock）
 * - 只 mock AgentModel（因為沒有真實 CLI agent 可用）
 * - 測試完整流程：config 讀取 → 關鍵字偵測 → 優先順序解析 → fallback → TierSelection
 */
class TierIntegrationTest {

    @TempDir
    Path tempDir;

    private GrimoConfig config;
    private AgentModelRegistry registry;
    private TierRouter router;
    private TierKeywordDetector keywordDetector;
    private AtomicReference<Tier> sessionTier;

    @BeforeEach
    void setUp() throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            agents:
              default: claude
              model: claude-sonnet-4
            agent-options:
              claude:
                model: claude-sonnet-4
              gemini:
                model: gemini-2.5-flash
            skill-tiers:
              lite:
                - agent: gemini
                  model: gemini-2.5-flash
                - agent: claude
                  model: claude-haiku-4
              std:
                - agent: claude
                  model: claude-sonnet-4
                - agent: gemini
                  model: gemini-2.5-pro
              pro:
                - agent: claude
                  model: claude-opus-4
                - agent: gemini
                  model: gemini-2.5-pro
            skill-overrides:
              deep-research:
                tier: pro
              tdd-workflow:
                agent: claude
                model: claude-opus-4
            tier-keywords:
              pro:
                - 仔細想
                - think hard
              lite:
                - 快速
                - quickly
            """);

        config = new GrimoConfig(configFile);
        registry = new AgentModelRegistry();
        sessionTier = new AtomicReference<>(null);

        // 真實 GrimoConfig + 真實 TierRouter + 真實 TierKeywordDetector
        router = new TierRouter(registry, config);
        keywordDetector = new TierKeywordDetector(config.getTierKeywords());
    }

    private void registerAgent(String id) {
        var model = mock(AgentModel.class);
        when(model.isAvailable()).thenReturn(true);
        registry.register(id, model);
    }

    // === 完整流程測試：模擬 GrimoTuiRunner.processInput 中的 tier 路由邏輯 ===

    private TierSelection resolveForInput(String userInput) {
        return resolveForInput(userInput, null);
    }

    private TierSelection resolveForInput(String userInput, String skillTier) {
        var keywordTier = keywordDetector.detect(userInput).orElse(null);
        var ctx = TierRouter.Context.builder()
                .keywordTier(keywordTier)
                .sessionTier(sessionTier.get())
                .skillTier(skillTier)
                .build();
        return router.resolve(ctx);
    }

    // --- 場景 1：一般對話，預設用 std tier ---

    @Test
    void normalConversationUsesStdTier() {
        registerAgent("claude");
        var selection = resolveForInput("重構 UserService 類別");
        assertThat(selection.tier()).isEqualTo(Tier.STD);
        assertThat(selection.agentId()).isEqualTo("claude");
        assertThat(selection.model()).isEqualTo("claude-sonnet-4");
    }

    // --- 場景 2：中文關鍵字「仔細想」提升到 pro ---

    @Test
    void chineseKeywordElevatesToPro() {
        registerAgent("claude");
        var selection = resolveForInput("仔細想 這段程式碼的問題在哪");
        assertThat(selection.tier()).isEqualTo(Tier.PRO);
        assertThat(selection.agentId()).isEqualTo("claude");
        assertThat(selection.model()).isEqualTo("claude-opus-4");
        assertThat(selection.source()).isEqualTo("keyword");
    }

    // --- 場景 3：英文關鍵字「quickly」降到 lite ---

    @Test
    void englishKeywordDropsToLite() {
        registerAgent("gemini");
        var selection = resolveForInput("quickly check if tests pass");
        assertThat(selection.tier()).isEqualTo(Tier.LITE);
        assertThat(selection.agentId()).isEqualTo("gemini");
        assertThat(selection.model()).isEqualTo("gemini-2.5-flash");
    }

    // --- 場景 4：Session tier 持續影響所有後續請求 ---

    @Test
    void sessionTierPersistsAcrossRequests() {
        registerAgent("claude");

        // 模擬 /tier pro 指令
        sessionTier.set(Tier.PRO);

        // 後續一般對話都用 pro tier
        var selection1 = resolveForInput("重構這個方法");
        assertThat(selection1.tier()).isEqualTo(Tier.PRO);
        assertThat(selection1.model()).isEqualTo("claude-opus-4");

        var selection2 = resolveForInput("寫測試");
        assertThat(selection2.tier()).isEqualTo(Tier.PRO);

        // /tier lite 切換
        sessionTier.set(Tier.LITE);
        var selection3 = resolveForInput("查詢結果");
        assertThat(selection3.tier()).isEqualTo(Tier.LITE);
    }

    // --- 場景 5：關鍵字優先於 session tier ---

    @Test
    void keywordOverridesSessionTier() {
        registerAgent("claude");
        registerAgent("gemini");
        sessionTier.set(Tier.PRO);

        // session 設定 pro，但輸入「快速」應降到 lite
        var selection = resolveForInput("快速 看一下編譯結果");
        assertThat(selection.tier()).isEqualTo(Tier.LITE);
        assertThat(selection.source()).isEqualTo("keyword");
    }

    // --- 場景 6：Fallback — 首選 agent 不可用，自動嘗試下一個 ---

    @Test
    void fallbackToNextAgentWhenPreferredUnavailable() {
        // pro tier 首選 claude，但只有 gemini 可用
        registerAgent("gemini");

        var selection = resolveForInput("仔細想 架構設計");
        assertThat(selection.tier()).isEqualTo(Tier.PRO);
        assertThat(selection.agentId()).isEqualTo("gemini");
        assertThat(selection.model()).isEqualTo("gemini-2.5-pro");
    }

    // --- 場景 7：所有 agent 都不可用 → 報錯 ---

    @Test
    void throwsWhenNoAgentAvailableForTier() {
        // 不註冊任何 agent
        assertThatThrownBy(() -> resolveForInput("仔細想 重構"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pro");
    }

    // --- 場景 8：skill-overrides 直接指定 agent+model ---

    @Test
    void skillOverrideDirectAgentBypassesTierResolution() {
        registerAgent("claude");

        var ctx = TierRouter.Context.builder()
                .skillName("tdd-workflow")
                .build();
        var selection = router.resolve(ctx);

        assertThat(selection.agentId()).isEqualTo("claude");
        assertThat(selection.model()).isEqualTo("claude-opus-4");
        assertThat(selection.source()).isEqualTo("skill-override-direct");
    }

    // --- 場景 9：skill-overrides 指定 tier（走 fallback list）---

    @Test
    void skillOverrideTierWalksFallbackList() {
        registerAgent("claude");

        var ctx = TierRouter.Context.builder()
                .skillName("deep-research")
                .build();
        var selection = router.resolve(ctx);

        assertThat(selection.tier()).isEqualTo(Tier.PRO);
        assertThat(selection.agentId()).isEqualTo("claude");
        assertThat(selection.model()).isEqualTo("claude-opus-4");
    }

    // --- 場景 10：skill metadata tier + keyword 覆寫 ---

    @Test
    void keywordOverridesSkillMetadataTier() {
        registerAgent("claude");
        registerAgent("gemini");

        // skill 標記 pro，但使用者輸入「快速」→ 降到 lite
        var keywordTier = keywordDetector.detect("快速 執行 skill").orElse(null);
        var ctx = TierRouter.Context.builder()
                .keywordTier(keywordTier)
                .skillTier("pro")
                .build();
        var selection = router.resolve(ctx);

        assertThat(selection.tier()).isEqualTo(Tier.LITE);
        assertThat(selection.source()).isEqualTo("keyword");
    }

    // --- 場景 11：/skill-tier 指令寫入 config 後立即生效 ---

    @Test
    void skillTierCommandPersistsAndTakesEffect() {
        registerAgent("claude");

        // 模擬 /skill-tier code-review lite
        config.setSkillOverride("code-review", java.util.Map.of("tier", "lite"));

        // 重建 router（因為 config 已更新）
        router = new TierRouter(registry, config);

        var ctx = TierRouter.Context.builder()
                .skillName("code-review")
                .build();
        var selection = router.resolve(ctx);

        assertThat(selection.tier()).isEqualTo(Tier.LITE);
        assertThat(selection.source()).isEqualTo("skill-override");
    }

    // --- 場景 12：config 未設定 skill-tiers 時 fallback 到 conversation default ---

    @Test
    void fallsBackToConversationDefaultWhenNoTierConfig() throws IOException {
        // 覆寫 config 為沒有 skill-tiers 的版本
        var configFile = tempDir.resolve("minimal-config.yaml");
        Files.writeString(configFile, """
            agents:
              default: claude
              model: claude-sonnet-4
            agent-options:
              claude:
                model: claude-sonnet-4
            """);
        var minimalConfig = new GrimoConfig(configFile);
        var minimalRouter = new TierRouter(registry, minimalConfig);

        registerAgent("claude");

        var ctx = TierRouter.Context.builder().build();
        var selection = minimalRouter.resolve(ctx);

        // 沒有 tier config → 用 conversation default
        assertThat(selection.agentId()).isEqualTo("claude");
    }

    // --- 場景 13：TierOptionsFactory 產出正確的 per-request options ---

    @Test
    void tierOptionsFactoryProducesCorrectOptionsForSelection() {
        registerAgent("claude");
        var selection = resolveForInput("仔細想 重構");
        var factory = new TierOptionsFactory();
        var options = factory.build(selection.agentId(), selection.model());

        assertThat(options.getModel()).isEqualTo("claude-opus-4");
    }
}
