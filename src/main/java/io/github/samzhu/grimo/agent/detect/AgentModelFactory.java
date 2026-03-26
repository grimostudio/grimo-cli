package io.github.samzhu.grimo.agent.detect;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agents.model.AgentModel;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * 啟動時建立所有已知 CLI AgentModel，用 Virtual Thread 並行偵測，可用的註冊到 registry。
 *
 * 設計說明：
 * - 取代 AgentDetector，改用各 AgentModel.isAvailable() 官方偵測
 * - 每個 AgentModel 的建立包在 try-catch 裡，某個 SDK 版本不合不影響其他 agent
 * - 使用 Virtual Thread 並行偵測，最慢的那個決定總耗時（而非累加）
 *
 * @see <a href="https://spring-ai-community.github.io/agent-client/">Spring AI Community Agent Client</a>
 */
public class AgentModelFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentModelFactory.class);

    private final AgentModelRegistry registry;
    private final List<AgentSpec> specs;

    public AgentModelFactory(AgentModelRegistry registry, List<AgentSpec> specs) {
        this.registry = registry;
        this.specs = specs;
    }

    public List<DetectionResult> detectAndRegister(Path workingDirectory) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<DetectionResult>> futures = specs.stream()
                    .map(spec -> executor.submit(() -> detectOne(spec, workingDirectory)))
                    .toList();

            return futures.stream()
                    .map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            log.warn("Detection future failed: {}", e.getMessage());
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    private DetectionResult detectOne(AgentSpec spec, Path workingDirectory) {
        try {
            AgentModel model = spec.creator().apply(workingDirectory);
            boolean available = model.isAvailable();
            if (available) {
                registry.register(spec.id(), model);
                log.info("Agent '{}' detected and registered", spec.id());
            } else {
                log.debug("Agent '{}' not available", spec.id());
            }
            return new DetectionResult(spec.id(), spec.type(), spec.detail(), available);
        } catch (Exception e) {
            log.warn("Agent '{}' creation failed: {}", spec.id(), e.getMessage());
            return new DetectionResult(spec.id(), spec.type(), spec.detail(), false);
        }
    }

    public void recreate(String agentId, Path workingDirectory) {
        specs.stream()
                .filter(s -> s.id().equals(agentId))
                .findFirst()
                .ifPresent(spec -> {
                    try {
                        AgentModel model = spec.creator().apply(workingDirectory);
                        registry.remove(agentId);
                        registry.register(agentId, model);
                        log.info("Agent '{}' recreated", agentId);
                    } catch (Exception e) {
                        log.error("Agent '{}' recreation failed: {}", agentId, e.getMessage());
                    }
                });
    }

    public record AgentSpec(String id, String type, String detail,
                            Function<Path, AgentModel> creator) {}

    public record DetectionResult(String id, String type, String detail, boolean available) {}
}
