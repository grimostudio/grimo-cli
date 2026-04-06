package io.github.samzhu.grimo.agent.tier;

import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tier 相關 CLI 指令。
 *
 * 設計說明：
 * - /tier：查看或切換 session tier（持續到下次切換）
 * - session tier 存在 AtomicReference 中，由 TierConfiguration 建立並注入
 */
@Component
public class TierCommands {

    private static final Set<String> VALID_TIERS = Set.of("lite", "std", "pro");

    private final AtomicReference<Tier> sessionTier;

    public TierCommands(AtomicReference<Tier> sessionTier) {
        this.sessionTier = sessionTier;
    }

    /**
     * 查看或設定 session tier。
     * /tier       → 顯示目前 tier
     * /tier pro   → 設定 session tier 為 pro
     *
     * @param rawArgs 原始參數字串，可為 null 或空（查看）、或 tier 名稱（設定）
     */
    @Command(name = "tier", description = "View or set the session tier (lite/std/pro)")
    public String tier(String rawArgs) {
        String level = (rawArgs != null) ? rawArgs.trim() : null;
        if (level == null || level.isBlank()) {
            Tier current = sessionTier.get();
            String tierName = current != null ? current.value() : "std (default)";
            return "Current session tier: " + tierName;
        }

        if (!VALID_TIERS.contains(level.toLowerCase())) {
            return "Unknown tier: '" + level + "'. Valid values: lite, std, pro";
        }

        Tier newTier = Tier.fromString(level);
        sessionTier.set(newTier);
        return newTier.icon() + " Session tier set to: " + newTier.value();
    }
}
