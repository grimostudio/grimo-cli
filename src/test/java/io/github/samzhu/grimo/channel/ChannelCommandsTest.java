package io.github.samzhu.grimo.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelCommandsTest {

    ChannelRegistry registry;
    ChannelCommands commands;

    @BeforeEach
    void setUp() {
        registry = new ChannelRegistry();
        commands = new ChannelCommands(registry);
    }

    @Test
    void listShouldShowChannels() {
        registry.register("telegram", stubAdapter("telegram", true));

        String output = commands.list();

        assertThat(output).contains("telegram");
        assertThat(output).contains("enabled");
    }

    @Test
    void listShouldShowDisabledStatus() {
        registry.register("line", stubAdapter("line", false));

        String output = commands.list();

        assertThat(output).contains("line");
        assertThat(output).contains("disabled");
    }

    @Test
    void listShouldShowEmptyMessage() {
        assertThat(commands.list()).contains("No channels configured");
    }

    @Test
    void removeShouldRemoveExistingChannel() {
        registry.register("telegram", stubAdapter("telegram", true));

        String output = commands.remove("telegram");

        assertThat(output).contains("Channel removed: telegram");
        assertThat(registry.get("telegram")).isEmpty();
    }

    @Test
    void removeShouldReturnNotFoundForMissingChannel() {
        String output = commands.remove("nonexistent");

        assertThat(output).contains("Channel not found: nonexistent");
    }

    private ChannelAdapter stubAdapter(String type, boolean enabled) {
        return new ChannelAdapter() {
            @Override public String channelType() { return type; }
            @Override public void send(OutgoingMessage msg) {}
            @Override public boolean isEnabled() { return enabled; }
        };
    }
}
