package io.github.samzhu.grimo;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.shell.core.command.CommandRegistry;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrimoCommandCompleterTest {

    SkillRegistry skillRegistry;
    CommandRegistry commandRegistry;
    GrimoCommandCompleter completer;

    @BeforeEach
    void setUp() {
        skillRegistry = new SkillRegistry();
        commandRegistry = mock(CommandRegistry.class);
        when(commandRegistry.getCommandsByPrefix(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(List.of());
        completer = new GrimoCommandCompleter(commandRegistry, skillRegistry);
    }

    @Test
    void slashPrefixShouldProvideSkillCandidatesWithSlashInValue() {
        skillRegistry.register(new SkillDefinition("healthcheck", "Check health", "1.0.0",
            "grimo-builtin", "api", List.of("cron"), "# HC"));
        var candidates = new ArrayList<Candidate>();
        var line = mockParsedLine("/", List.of("/"));
        completer.complete(mock(LineReader.class), line, candidates);
        // Candidate value 包含 / 前綴，讓 JLine 能正確過濾
        assertThat(candidates).anyMatch(c -> c.value().equals("/skill healthcheck"));
    }

    @Test
    void slashPrefixShouldReturnAllCandidatesForJLineFiltering() {
        skillRegistry.register(new SkillDefinition("healthcheck", "Check health", "1.0.0",
            "grimo-builtin", "api", List.of(), "# HC"));
        var candidates = new ArrayList<Candidate>();
        var line = mockParsedLine("/hea", List.of("/hea"));
        completer.complete(mock(LineReader.class), line, candidates);
        // 返回所有候選項，由 JLine 負責依 /hea 過濾
        assertThat(candidates).anyMatch(c -> c.value().startsWith("/skill"));
    }

    @Test
    void nonSlashInputShouldDelegateToParent() {
        var candidates = new ArrayList<Candidate>();
        var line = mockParsedLine("stat", List.of("stat"));
        completer.complete(mock(LineReader.class), line, candidates);
        // Just verify no exception thrown — parent returns empty since we mocked empty registry
    }

    private ParsedLine mockParsedLine(String line, List<String> words) {
        var parsed = mock(ParsedLine.class);
        when(parsed.line()).thenReturn(line);
        when(parsed.words()).thenReturn(words);
        when(parsed.word()).thenReturn(words.isEmpty() ? "" : words.getLast());
        when(parsed.wordIndex()).thenReturn(0);
        when(parsed.wordCursor()).thenReturn(line.length());
        return parsed;
    }
}
