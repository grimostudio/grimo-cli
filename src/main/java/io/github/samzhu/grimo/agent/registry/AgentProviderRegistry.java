package io.github.samzhu.grimo.agent.registry;

import io.github.samzhu.grimo.agent.provider.AgentProvider;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class AgentProviderRegistry {

    private final ConcurrentHashMap<String, AgentProvider> providers = new ConcurrentHashMap<>();

    public void register(String id, AgentProvider provider) {
        providers.put(id, provider);
    }

    public void remove(String id) {
        providers.remove(id);
    }

    public Optional<AgentProvider> get(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    public List<AgentProvider> listAll() {
        return List.copyOf(providers.values());
    }

    public List<AgentProvider> listAvailable() {
        return providers.values().stream()
            .filter(AgentProvider::isAvailable)
            .toList();
    }
}
