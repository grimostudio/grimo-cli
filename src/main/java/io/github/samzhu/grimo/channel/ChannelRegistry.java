package io.github.samzhu.grimo.channel;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for managing {@link ChannelAdapter} instances at runtime.
 * Uses {@link ConcurrentHashMap} instead of Spring DI to support dynamic
 * registration and removal of channel adapters via CLI commands.
 */
public class ChannelRegistry {

    private final ConcurrentHashMap<String, ChannelAdapter> adapters = new ConcurrentHashMap<>();

    public void register(String channelType, ChannelAdapter adapter) {
        adapters.put(channelType, adapter);
    }

    public void remove(String channelType) {
        adapters.remove(channelType);
    }

    public Optional<ChannelAdapter> get(String channelType) {
        return Optional.ofNullable(adapters.get(channelType));
    }

    public List<ChannelAdapter> listAll() {
        return List.copyOf(adapters.values());
    }

    public List<ChannelAdapter> listEnabled() {
        return adapters.values().stream()
            .filter(ChannelAdapter::isEnabled)
            .toList();
    }
}
