package io.github.samzhu.grimo.shared.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SessionIndexTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void shouldSerializeAndDeserialize() throws Exception {
        var entry = new SessionIndex.Entry(
            "a3f1b2c4", Instant.parse("2026-04-05T10:30:00Z"),
            Instant.parse("2026-04-05T11:45:00Z"), 24,
            "幫我重構 auth module", "main", "claude", "opus");
        var index = new SessionIndex(1, List.of(entry));

        String json = mapper.writeValueAsString(index);
        var deserialized = mapper.readValue(json, SessionIndex.class);

        assertThat(deserialized.version()).isEqualTo(1);
        assertThat(deserialized.sessions()).hasSize(1);
        assertThat(deserialized.sessions().getFirst().sessionId()).isEqualTo("a3f1b2c4");
        assertThat(deserialized.sessions().getFirst().firstUserMessage()).isEqualTo("幫我重構 auth module");
    }

    @Test
    void shouldHandleUnknownFields() throws Exception {
        String json = """
            {"version":1,"sessions":[{"sessionId":"abc","startedAt":"2026-04-05T10:30:00Z","lastActiveAt":"2026-04-05T10:30:00Z","messageCount":0,"firstUserMessage":null,"gitBranch":"main","agent":"claude","model":"opus","futureField":"ignored"}]}
            """;
        var index = mapper.readValue(json, SessionIndex.class);
        assertThat(index.sessions()).hasSize(1);
    }
}
