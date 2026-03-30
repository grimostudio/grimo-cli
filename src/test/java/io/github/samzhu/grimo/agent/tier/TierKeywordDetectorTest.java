package io.github.samzhu.grimo.agent.tier;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TierKeywordDetectorTest {

    private final Map<String, List<String>> keywords = Map.of(
            "pro", List.of("仔細想", "深入分析", "think hard", "think deeply"),
            "lite", List.of("快速", "簡單說", "quickly", "briefly")
    );

    private final TierKeywordDetector detector = new TierKeywordDetector(keywords);

    @Test
    void detectProKeywordInChinese() {
        assertThat(detector.detect("仔細想 這段程式碼的問題")).hasValue(Tier.PRO);
    }

    @Test
    void detectLiteKeywordInEnglish() {
        assertThat(detector.detect("quickly check the test results")).hasValue(Tier.LITE);
    }

    @Test
    void detectNoKeyword() {
        assertThat(detector.detect("refactor the UserService class")).isEmpty();
    }

    @Test
    void detectReturnsHighestTierWhenMultipleMatch() {
        assertThat(detector.detect("快速 但仔細想一下")).hasValue(Tier.PRO);
    }

    @Test
    void detectIsCaseInsensitive() {
        assertThat(detector.detect("THINK HARD about this")).hasValue(Tier.PRO);
    }

    @Test
    void detectWithEmptyKeywordsReturnsEmpty() {
        var emptyDetector = new TierKeywordDetector(Map.of());
        assertThat(emptyDetector.detect("仔細想")).isEmpty();
    }

    @Test
    void detectWithNullInputReturnsEmpty() {
        assertThat(detector.detect(null)).isEmpty();
    }
}
