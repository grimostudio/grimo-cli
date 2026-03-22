package io.github.samzhu.grimo.skill.loader;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads skill definitions from the workspace skills directory.
 * Each skill is a subdirectory containing a SKILL.md file with YAML frontmatter
 * (delimited by ---) followed by a Markdown body describing the skill's behavior.
 */
public class SkillLoader {

    private final Path skillsDir;

    public SkillLoader(Path skillsDir) {
        this.skillsDir = skillsDir;
    }

    /**
     * Scans all subdirectories under skillsDir, loading any SKILL.md found.
     */
    public List<SkillDefinition> loadAll() {
        if (!Files.isDirectory(skillsDir)) return List.of();

        var skills = new ArrayList<SkillDefinition>();
        try (Stream<Path> dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory)
                .forEach(dir -> {
                    Path skillMd = dir.resolve("SKILL.md");
                    if (Files.exists(skillMd)) {
                        skills.add(parseSkillMd(skillMd));
                    }
                });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan skills directory", e);
        }
        return skills;
    }

    /**
     * Loads a single SKILL.md file.
     */
    public SkillDefinition load(Path skillMdPath) {
        return parseSkillMd(skillMdPath);
    }

    /**
     * Parses a SKILL.md file by splitting on '---' delimiters to extract
     * YAML frontmatter (metadata) and Markdown body (skill instructions).
     */
    @SuppressWarnings("unchecked")
    private SkillDefinition parseSkillMd(Path path) {
        try {
            String content = Files.readString(path);
            String[] parts = content.split("---", 3);
            if (parts.length < 3) {
                throw new IllegalArgumentException("Invalid SKILL.md: missing frontmatter in " + path);
            }

            Yaml yaml = new Yaml();
            Map<String, Object> fm = yaml.load(parts[1]);
            String body = parts[2].strip();

            List<String> triggers = fm.containsKey("triggers")
                ? ((List<Object>) fm.get("triggers")).stream().map(Object::toString).toList()
                : List.of();

            return new SkillDefinition(
                (String) fm.get("name"),
                (String) fm.get("description"),
                (String) fm.get("version"),
                (String) fm.get("author"),
                (String) fm.get("executor"),
                triggers,
                body
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read SKILL.md: " + path, e);
        }
    }
}
