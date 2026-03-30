package io.github.samzhu.grimo.skill.analyzer;

import io.github.samzhu.grimo.agent.tier.Tier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillAnalyzerTest {

    @Test
    void parseResponseShouldExtractProTier() {
        var result = SkillAnalyzer.parseResponse("""
            { "tier": "pro", "reason": "多步驟研究流程" }
            """);
        assertThat(result.tier()).isEqualTo(Tier.PRO);
        assertThat(result.reason()).contains("多步驟");
    }

    @Test
    void parseResponseShouldExtractLiteTier() {
        var result = SkillAnalyzer.parseResponse("""
            { "tier": "lite", "reason": "simple query" }
            """);
        assertThat(result.tier()).isEqualTo(Tier.LITE);
    }

    @Test
    void parseResponseShouldReturnStdWhenInvalid() {
        var result = SkillAnalyzer.parseResponse("invalid response");
        assertThat(result.tier()).isEqualTo(Tier.STD);
    }

    @Test
    void parseResponseShouldReturnStdWhenNull() {
        var result = SkillAnalyzer.parseResponse(null);
        assertThat(result.tier()).isEqualTo(Tier.STD);
    }

    @Test
    void parseResponseShouldHandleJsonWithExtraText() {
        var result = SkillAnalyzer.parseResponse("""
            Based on my analysis:
            { "tier": "pro", "reason": "cross-file refactoring needed" }
            Hope this helps!
            """);
        assertThat(result.tier()).isEqualTo(Tier.PRO);
    }
}
