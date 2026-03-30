package io.github.samzhu.grimo.agent.tier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier enum 的邊界案例測試。
 */
class TierTest {

    @Test
    void enumValuesAreCorrect() {
        assertThat(Tier.LITE.value()).isEqualTo("lite");
        assertThat(Tier.STD.value()).isEqualTo("std");
        assertThat(Tier.PRO.value()).isEqualTo("pro");
    }

    @Test
    void iconsAreNonEmpty() {
        for (Tier tier : Tier.values()) {
            assertThat(tier.icon()).isNotEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "lite, LITE",
        "LITE, LITE",
        "Lite, LITE",
        "std, STD",
        "STD, STD",
        "pro, PRO",
        "PRO, PRO",
        "Pro, PRO"
    })
    void fromStringParsesValidValues(String input, Tier expected) {
        assertThat(Tier.fromString(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "medium", "fast", "premium", "  "})
    void fromStringDefaultsToStdForUnknownValues(String input) {
        assertThat(Tier.fromString(input)).isEqualTo(Tier.STD);
    }

    @Test
    void fromStringHandlesNull() {
        assertThat(Tier.fromString(null)).isEqualTo(Tier.STD);
    }

    @Test
    void fromStringTrimsWhitespace() {
        assertThat(Tier.fromString("  pro  ")).isEqualTo(Tier.PRO);
        assertThat(Tier.fromString("\tlite\n")).isEqualTo(Tier.LITE);
    }
}
