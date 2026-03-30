package io.github.samzhu.grimo.agent.tier;

/**
 * Skill 執行的能力等級。
 *
 * 設計說明：
 * - 三級對應不同成本/能力：lite（快速便宜）、std（日常主力）、pro（深度推理）
 * - 對齊 MindStudio 3-Tier Router 業界標準（Fast/Standard/Premium）
 *
 * @see <a href="https://www.mindstudio.ai/blog/set-up-ai-model-router-llm-stack-c2610">MindStudio 3-Tier</a>
 */
public enum Tier {
    LITE("lite", "⚡"),
    STD("std", "⚙"),
    PRO("pro", "\uD83E\uDDE0");

    private final String value;
    private final String icon;

    Tier(String value, String icon) {
        this.value = value;
        this.icon = icon;
    }

    public String value() { return value; }
    public String icon() { return icon; }

    /**
     * 從字串解析 Tier，不區分大小寫。無效值回傳 STD。
     */
    public static Tier fromString(String s) {
        if (s == null) return STD;
        return switch (s.strip().toLowerCase()) {
            case "lite" -> LITE;
            case "pro" -> PRO;
            default -> STD;
        };
    }
}
