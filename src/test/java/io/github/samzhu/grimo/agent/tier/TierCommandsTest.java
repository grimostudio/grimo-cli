package io.github.samzhu.grimo.agent.tier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TierCommandsTest {

    private TierCommands commands;
    private AtomicReference<Tier> sessionTierRef;

    @BeforeEach
    void setUp() {
        sessionTierRef = new AtomicReference<>(null);
        commands = new TierCommands(sessionTierRef);
    }

    @Test
    void tierShowShouldDisplayCurrentTier() {
        String output = commands.tier(null);
        assertThat(output).contains("std"); // default
    }

    @Test
    void tierSetShouldUpdateSessionTier() {
        String output = commands.tier("pro");
        assertThat(output).contains("pro");
        assertThat(sessionTierRef.get()).isEqualTo(Tier.PRO);
    }

    @Test
    void tierSetInvalidShouldWarnUser() {
        String output = commands.tier("invalid");
        assertThat(output).contains("Unknown tier");
        assertThat(sessionTierRef.get()).isNull(); // unchanged
    }
}
