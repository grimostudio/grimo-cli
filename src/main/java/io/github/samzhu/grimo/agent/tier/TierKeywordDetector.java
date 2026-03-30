package io.github.samzhu.grimo.agent.tier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 從使用者輸入文字偵測 tier 關鍵字（per-turn 提升）。
 *
 * 設計說明：
 * - 關鍵字只影響該輪，不改 session 設定（類似 Claude Code 的 ultrathink 機制）
 * - 多個 tier 同時匹配時取最高等級（PRO > STD > LITE）
 * - 不區分大小寫（英文），中文直接子字串比對
 *
 * @see <a href="https://findskill.ai/blog/claude-ultrathink-extended-thinking/">Claude Code ultrathink</a>
 */
public class TierKeywordDetector {

    private final Map<String, List<String>> keywords;

    public TierKeywordDetector(Map<String, List<String>> keywords) {
        this.keywords = keywords;
    }

    /**
     * 偵測使用者輸入中的 tier 關鍵字。
     *
     * @param input 使用者輸入文字
     * @return 偵測到的 Tier，或 empty 表示無匹配
     */
    public Optional<Tier> detect(String input) {
        if (input == null || keywords.isEmpty()) {
            return Optional.empty();
        }
        String lower = input.toLowerCase();

        // 按 PRO > STD > LITE 順序檢查，回傳最高匹配
        for (Tier tier : List.of(Tier.PRO, Tier.STD, Tier.LITE)) {
            List<String> tierKeywords = keywords.getOrDefault(tier.value(), List.of());
            for (String keyword : tierKeywords) {
                if (lower.contains(keyword.toLowerCase())) {
                    return Optional.of(tier);
                }
            }
        }
        return Optional.empty();
    }
}
