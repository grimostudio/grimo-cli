package io.github.samzhu.grimo.shared.event;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DispatchLifecycleEventTest {

    @Test
    void allEventsImplementSealedInterface() {
        DispatchLifecycleEvent queued = new DispatchQueuedEvent("hello");
        DispatchLifecycleEvent thinking = new DispatchThinkingStartedEvent("claude", "sonnet");
        DispatchLifecycleEvent tool = new DispatchToolCalledEvent("claude", "read_file");
        DispatchLifecycleEvent coding = new DispatchCodingEvent("claude", "/src/Main.java");
        DispatchLifecycleEvent web = new DispatchWebSearchEvent("claude", "spring modulith");
        DispatchLifecycleEvent response = new DispatchResponseReceivedEvent("claude", "sonnet", 5000);
        DispatchLifecycleEvent completed = new DispatchCompletedEvent("claude", "sonnet", 5000, 1024);
        DispatchLifecycleEvent failed = new DispatchFailedEvent("claude", "sonnet", "timeout", 120000);

        assertThat(queued).isInstanceOf(DispatchLifecycleEvent.class);
        assertThat(thinking).isInstanceOf(DispatchLifecycleEvent.class);
        assertThat(tool).isInstanceOf(DispatchLifecycleEvent.class);
        assertThat(coding).isInstanceOf(DispatchLifecycleEvent.class);
        assertThat(web).isInstanceOf(DispatchLifecycleEvent.class);
        assertThat(response).isInstanceOf(DispatchLifecycleEvent.class);
        assertThat(completed).isInstanceOf(DispatchLifecycleEvent.class);
        assertThat(failed).isInstanceOf(DispatchLifecycleEvent.class);
    }

    @Test
    void patternMatchingOnSealedType() {
        DispatchLifecycleEvent event = new DispatchThinkingStartedEvent("claude", "sonnet");
        String result = switch (event) {
            case DispatchQueuedEvent e -> "queued";
            case DispatchThinkingStartedEvent e -> "thinking:" + e.agentId();
            case DispatchToolCalledEvent e -> "tool:" + e.toolName();
            case DispatchCodingEvent e -> "coding";
            case DispatchWebSearchEvent e -> "web";
            case DispatchResponseReceivedEvent e -> "response";
            case DispatchCompletedEvent e -> "completed";
            case DispatchFailedEvent e -> "failed";
        };
        assertThat(result).isEqualTo("thinking:claude");
    }

    @Test
    void failedEventAllowsNullAgentAndModel() {
        var event = new DispatchFailedEvent(null, null, "routing failed", 0);
        assertThat(event.agentId()).isNull();
        assertThat(event.model()).isNull();
        assertThat(event.errorMessage()).isEqualTo("routing failed");
    }
}
