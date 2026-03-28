package io.github.samzhu.grimo.skill.loader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 從 workspace skills 目錄載入 SKILL.md，解析為 SkillDefinition。
 *
 * 設計說明：
 * - 對齊 Agent Skills 開放標準（agentskills.io/specification）的 SKILL.md 格式
 * - 支援 Claude Code 擴充欄位（model, effort, context, agent 等）
 * - 向後相容：舊格式的 version/author 自動映射到 metadata，triggers/executor 忽略並 WARN
 * - Forward compatibility：未知欄位直接忽略，不報錯
 *
 * @see <a href="https://agentskills.io/specification">Agent Skills Specification</a>
 */
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

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
     * 解析 SKILL.md：YAML frontmatter + Markdown body。
     *
     * 設計說明：
     * - 標準欄位直接提取（name, description, license, compatibility, allowed-tools, metadata）
     * - Claude Code 欄位直接提取（model, effort, context, agent, user-invocable 等）
     * - 舊格式 version/author → metadata 自動遷移 + WARN log
     * - 舊格式 triggers/executor → 忽略 + WARN log
     * - 未知欄位 → 靜默忽略（forward compatibility）
     *
     * @see <a href="https://agentskills.io/specification">Agent Skills Specification — Frontmatter</a>
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
            if (fm == null) fm = Map.of();
            String body = parts[2].strip();

            // --- Agent Skills 標準欄位 ---
            String name = getString(fm, "name");
            String description = getString(fm, "description");
            String license = getString(fm, "license");
            String compatibility = getString(fm, "compatibility");

            // allowed-tools: 空格分隔字串 → List<String>
            List<String> allowedTools = List.of();
            Object rawAllowedTools = fm.get("allowed-tools");
            if (rawAllowedTools instanceof String s && !s.isBlank()) {
                allowedTools = Arrays.asList(s.split("\\s+"));
            }

            // metadata: Map<String, Object> → Map<String, String>
            Map<String, String> metadata = new LinkedHashMap<>();
            Object rawMetadata = fm.get("metadata");
            if (rawMetadata instanceof Map<?, ?> m) {
                m.forEach((k, v) -> metadata.put(k.toString(), v != null ? v.toString() : ""));
            }

            // --- 向後相容：舊格式 version/author 自動遷移到 metadata ---
            if (fm.containsKey("version") && !metadata.containsKey("grimo.version")) {
                metadata.put("grimo.version", fm.get("version").toString());
                log.warn("SKILL.md '{}': 'version' is deprecated, use metadata.grimo.version instead", name);
            }
            if (fm.containsKey("author") && !metadata.containsKey("grimo.author")) {
                metadata.put("grimo.author", fm.get("author").toString());
                log.warn("SKILL.md '{}': 'author' is deprecated, use metadata.grimo.author instead", name);
            }
            if (fm.containsKey("triggers")) {
                log.warn("SKILL.md '{}': 'triggers' is deprecated and ignored", name);
            }
            if (fm.containsKey("executor")) {
                log.warn("SKILL.md '{}': 'executor' is deprecated and ignored", name);
            }

            // --- Claude Code 擴充欄位 ---
            String model = getString(fm, "model");
            String effort = getString(fm, "effort");
            String context = getString(fm, "context");
            String agent = getString(fm, "agent");
            Boolean userInvocable = getBoolean(fm, "user-invocable");
            Boolean disableModelInvocation = getBoolean(fm, "disable-model-invocation");
            String argumentHint = getString(fm, "argument-hint");

            List<String> paths = List.of();
            Object rawPaths = fm.get("paths");
            if (rawPaths instanceof List<?> list) {
                paths = list.stream().map(Object::toString).toList();
            }

            String shell = getString(fm, "shell");

            return new SkillDefinition(
                name, description, license, compatibility, allowedTools, metadata,
                model, effort, context, agent, userInvocable, disableModelInvocation, argumentHint, paths, shell,
                body
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read SKILL.md: " + path, e);
        }
    }

    private static String getString(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        return v != null ? v.toString() : null;
    }

    private static Boolean getBoolean(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.valueOf(s);
        return null;
    }
}
