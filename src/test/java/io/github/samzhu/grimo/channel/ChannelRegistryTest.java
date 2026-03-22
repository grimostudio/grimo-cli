package io.github.samzhu.grimo.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelRegistryTest {

    ChannelRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ChannelRegistry();
    }

    @Test
    void registerAndGetAdapter() {
        var adapter = stubAdapter("telegram", true);
        registry.register("telegram", adapter);

        assertThat(registry.get("telegram")).isPresent();
    }

    @Test
    void listEnabledAdapters() {
        registry.register("telegram", stubAdapter("telegram", true));
        registry.register("line", stubAdapter("line", false));

        assertThat(registry.listEnabled()).hasSize(1);
        assertThat(registry.listEnabled().getFirst().channelType()).isEqualTo("telegram");
    }

    @Test
    void removeAdapter() {
        registry.register("telegram", stubAdapter("telegram", true));
        registry.remove("telegram");

        assertThat(registry.get("telegram")).isEmpty();
    }

    private ChannelAdapter stubAdapter(String type, boolean enabled) {
        return new ChannelAdapter() {
            @Override public String channelType() { return type; }
            @Override public void send(OutgoingMessage msg) {}
            @Override public boolean isEnabled() { return enabled; }
        };
    }
}
