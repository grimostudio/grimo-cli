package io.github.samzhu.grimo.task.store;

import io.github.samzhu.grimo.task.model.Task;
import io.github.samzhu.grimo.task.model.TaskStatus;
import io.github.samzhu.grimo.task.model.TaskType;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * 以 Markdown 檔案持久化 Task，使用 YAML frontmatter 儲存元資料。
 *
 * 設計說明：
 * - 每個 Task 對應一個 .md 檔案，檔名為 task id
 * - 檔案開頭以 --- 包裹的 YAML frontmatter 存放 id、type、status、cron 等欄位
 * - frontmatter 之後為 markdown body，包含任務描述與執行紀錄
 * - 使用 SnakeYAML 進行 YAML 序列化/反序列化（Spring Boot 已帶入此依賴）
 */
public class MarkdownTaskStore {

    private final Path tasksDir;

    public MarkdownTaskStore(Path tasksDir) {
        this.tasksDir = tasksDir;
    }

    /**
     * 將 Task 序列化為 Markdown 檔案，寫入 tasksDir/{id}.md。
     */
    public void save(Task task) {
        var frontmatter = new LinkedHashMap<String, Object>();
        frontmatter.put("id", task.id());
        frontmatter.put("type", task.type().name().toLowerCase());
        frontmatter.put("status", task.status().name().toLowerCase());
        if (task.cron() != null) frontmatter.put("cron", task.cron());
        if (task.channel() != null) frontmatter.put("channel", task.channel());
        frontmatter.put("created", task.created().toString());
        if (task.lastRun() != null) frontmatter.put("last_run", task.lastRun().toString());
        if (task.nextRun() != null) frontmatter.put("next_run", task.nextRun().toString());

        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String yaml = new Yaml(options).dump(frontmatter);

        String content = "---\n" + yaml + "---\n\n" + (task.body() != null ? task.body() : "");

        try {
            Files.writeString(tasksDir.resolve(task.id() + ".md"), content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save task: " + task.id(), e);
        }
    }

    /**
     * 根據 taskId 載入對應的 .md 檔案並解析為 Task。
     */
    public Optional<Task> load(String taskId) {
        Path file = tasksDir.resolve(taskId + ".md");
        if (!Files.exists(file)) return Optional.empty();

        try {
            return Optional.of(parseMarkdown(Files.readString(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load task: " + taskId, e);
        }
    }

    /**
     * 載入 tasksDir 中所有 .md 檔案並解析為 Task 清單。
     */
    public List<Task> loadAll() {
        try (Stream<Path> files = Files.list(tasksDir)) {
            return files
                .filter(p -> p.toString().endsWith(".md"))
                .map(p -> {
                    try {
                        return parseMarkdown(Files.readString(p));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list tasks", e);
        }
    }

    /**
     * 在指定 task 的 Markdown 檔案末尾追加執行紀錄區塊。
     * 若尚未有「執行紀錄」章節則自動建立。
     */
    public void appendExecutionLog(String taskId, String timestamp, String log) {
        Path file = tasksDir.resolve(taskId + ".md");
        try {
            String content = Files.readString(file);
            String logSection = "\n\n## 執行紀錄\n";
            if (!content.contains("## 執行紀錄")) {
                content += logSection;
            }
            content += "\n### " + timestamp + "\n" + log + "\n";
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append log to task: " + taskId, e);
        }
    }

    /**
     * 刪除指定 taskId 對應的 .md 檔案。
     */
    public void delete(String taskId) {
        try {
            Files.deleteIfExists(tasksDir.resolve(taskId + ".md"));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete task: " + taskId, e);
        }
    }

    /**
     * 解析 Markdown 內容（含 YAML frontmatter）為 Task record。
     * 格式預期：--- \n YAML \n --- \n\n body
     */
    @SuppressWarnings("unchecked")
    private Task parseMarkdown(String content) {
        String[] parts = content.split("---", 3);
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid task markdown: missing frontmatter");
        }

        Yaml yaml = new Yaml();
        Map<String, Object> fm = yaml.load(parts[1]);
        String body = parts[2].strip();

        return new Task(
            (String) fm.get("id"),
            TaskType.valueOf(((String) fm.get("type")).toUpperCase()),
            TaskStatus.valueOf(((String) fm.get("status")).toUpperCase()),
            body.lines().findFirst().map(l -> l.replaceFirst("^#+\\s*", "")).orElse(""),
            (String) fm.get("cron"),
            fm.containsKey("delay_seconds") ? ((Number) fm.get("delay_seconds")).longValue() : null,
            (String) fm.get("channel"),
            fm.containsKey("created") ? Instant.parse((String) fm.get("created")) : null,
            fm.containsKey("last_run") ? Instant.parse((String) fm.get("last_run")) : null,
            fm.containsKey("next_run") ? Instant.parse((String) fm.get("next_run")) : null,
            body
        );
    }
}
