package io.github.samzhu.grimo.agent.tier;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.config.GrimoConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Tier 系統的 Spring Bean 定義。
 *
 * 設計說明：
 * - TierRouter, TierKeywordDetector, TierOptionsFactory 都是 plain Java objects，
 *   透過 @Bean 工廠方法建立（Library over Starter 原則）
 * - sessionTier 用 AtomicReference 在 TierCommands 和 GrimoTuiRunner 間共享
 */
@Configuration
public class TierConfiguration {

    @Bean
    public TierRouter tierRouter(AgentModelRegistry registry, GrimoConfig config) {
        return new TierRouter(registry, config);
    }

    @Bean
    public TierKeywordDetector tierKeywordDetector(GrimoConfig config) {
        return new TierKeywordDetector(config.getTierKeywords());
    }

    @Bean
    public TierOptionsFactory tierOptionsFactory() {
        return new TierOptionsFactory();
    }

    /**
     * Session 級 tier 狀態：/tier 指令設定，持續到下次切換。
     * GrimoTuiRunner 和 TierCommands 共享此 reference。
     */
    @Bean
    public AtomicReference<Tier> sessionTier() {
        return new AtomicReference<>(null);
    }
}
