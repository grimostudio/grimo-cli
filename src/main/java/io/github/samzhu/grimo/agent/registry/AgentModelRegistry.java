package io.github.samzhu.grimo.agent.registry;

import org.springaicommunity.agents.model.AgentModel;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe registry for AgentModel instances.
 *
 * 設計說明：
 * - 取代 AgentProviderRegistry，型別從 AgentProvider 換成 AgentModel
 * - 使用 ConcurrentHashMap 支援 runtime 動態增刪（Library over Starter 原則）
 * - listAvailable() 每次呼叫 re-check isAvailable()，因為用戶可能中途安裝/移除 CLI
 * - @NamedInterface 已由 package-info.java 宣告，不重複加在類別上
 */
@Component
public class AgentModelRegistry {

    private final ConcurrentHashMap<String, AgentModel> models = new ConcurrentHashMap<>();

    public void register(String id, AgentModel model) {
        models.put(id, model);
    }

    public void remove(String id) {
        models.remove(id);
    }

    public AgentModel get(String id) {
        return models.get(id);
    }

    public Map<String, AgentModel> listAll() {
        return Map.copyOf(models);
    }

    public Map<String, AgentModel> listAvailable() {
        return models.entrySet().stream()
                .filter(e -> e.getValue().isAvailable())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
