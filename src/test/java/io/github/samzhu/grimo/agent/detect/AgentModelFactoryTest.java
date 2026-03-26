package io.github.samzhu.grimo.agent.detect;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.model.AgentModel;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentModelFactoryTest {

    @Test
    void detectRegistersAvailableModels() {
        var registry = new AgentModelRegistry();
        var availableModel = mock(AgentModel.class);
        when(availableModel.isAvailable()).thenReturn(true);

        var spec = new AgentModelFactory.AgentSpec("test-agent", "cli", "Test Agent",
                path -> availableModel);

        var factory = new AgentModelFactory(registry, List.of(spec));
        var results = factory.detectAndRegister(Path.of("."));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().available()).isTrue();
        assertThat(registry.get("test-agent")).isSameAs(availableModel);
    }

    @Test
    void detectSkipsUnavailableModels() {
        var registry = new AgentModelRegistry();
        var unavailableModel = mock(AgentModel.class);
        when(unavailableModel.isAvailable()).thenReturn(false);

        var spec = new AgentModelFactory.AgentSpec("missing-agent", "cli", "Missing",
                path -> unavailableModel);

        var factory = new AgentModelFactory(registry, List.of(spec));
        var results = factory.detectAndRegister(Path.of("."));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().available()).isFalse();
        assertThat(registry.get("missing-agent")).isNull();
    }

    @Test
    void detectIsolatesCreationFailures() {
        var registry = new AgentModelRegistry();
        var goodModel = mock(AgentModel.class);
        when(goodModel.isAvailable()).thenReturn(true);

        var specs = List.of(
                new AgentModelFactory.AgentSpec("good", "cli", "Good Agent", path -> goodModel),
                new AgentModelFactory.AgentSpec("broken", "cli", "Broken Agent", path -> {
                    throw new RuntimeException("SDK missing");
                })
        );

        var factory = new AgentModelFactory(registry, specs);
        var results = factory.detectAndRegister(Path.of("."));

        assertThat(results).hasSize(2);
        assertThat(registry.get("good")).isSameAs(goodModel);
        assertThat(registry.get("broken")).isNull();
    }
}
