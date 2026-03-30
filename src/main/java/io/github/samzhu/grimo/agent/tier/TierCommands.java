package io.github.samzhu.grimo.agent.tier;

import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.springframework.lang.Nullable;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tier 相關 CLI 指令。
 *
 * 設計說明：
 * - /tier：查看或切換 session tier（持續到下次切換）
 * - /skill-tier：覆寫特定 skill 的 tier（寫入 config 永久保存）
 * - session tier 存在 AtomicReference 中，由 TierConfiguration 建立並注入
 */
@Component
public class TierCommands {

    private static final Set<String> VALID_TIERS = Set.of("lite", "std", "pro");

    private final GrimoConfig config;
    private final AtomicReference<Tier> sessionTier;

    public TierCommands(GrimoConfig config, AtomicReference<Tier> sessionTier) {
        this.config = config;
        this.sessionTier = sessionTier;
    }

    /**
     * 查看或設定 session tier。
     * /tier       → 顯示目前 tier
     * /tier pro   → 設定 session tier 為 pro
     */
    @Command(name = "tier", description = "View or set the session tier (lite/std/pro)")
    public String tier(@Nullable String level) {
        if (level == null || level.isBlank()) {
            Tier current = sessionTier.get();
            String tierName = current != null ? current.value() : "std (default)";
            return "Current session tier: " + tierName;
        }

        if (!VALID_TIERS.contains(level.strip().toLowerCase())) {
            return "Unknown tier: '" + level + "'. Valid values: lite, std, pro";
        }

        Tier newTier = Tier.fromString(level);
        sessionTier.set(newTier);
        return newTier.icon() + " Session tier set to: " + newTier.value();
    }

    /**
     * 覆寫特定 skill 的 tier（永久寫入 config.yaml）。
     * /skill-tier deep-research pro
     */
    @Command(name = "skill-tier", description = "Override tier for a specific skill")
    public String skillTier(String skillName, String tierLevel) {
        Tier tier = Tier.fromString(tierLevel);
        config.setSkillOverride(skillName, Map.of("tier", tier.value()));
        return "Skill '" + skillName + "' tier override set to: " + tier.value()
                + " (saved to config.yaml)";
    }
}
