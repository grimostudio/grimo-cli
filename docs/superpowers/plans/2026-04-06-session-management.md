# Session Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add session lifecycle management (list, resume, continue) to Grimo CLI so users can switch between and revisit past conversations.

**Architecture:** SessionManager becomes the central session lifecycle coordinator, owning SessionWriter and maintaining sessions-index.json. Resume loads the original JSONL and replays messages to TUI. New overlay (SessionPickerOverlay) and CLI args (`--continue`, `--resume`) provide access.

**Tech Stack:** Java 25, Spring Boot 4.0.x, Spring Shell 4.0.x, JLine 3, Jackson (JSON), JUnit 5 + AssertJ

**Spec:** `docs/superpowers/specs/2026-04-06-session-management-design.md`

---

### File Structure

**New files:**

| File | Responsibility |
|------|---------------|
| `shared/session/SessionIndex.java` | Record: sessions-index.json data model |
| `shared/session/SessionMessage.java` | Record: JSONL message read model for replay |
| `shared/session/SessionManager.java` | Session lifecycle, index maintenance, list/resume/info API |
| `shared/event/SessionSwitchedEvent.java` | Event: session switch notification for TUI |
| `command/SessionCommands.java` | `/session-resume`, `/session-info` commands |
| `tui/overlay/SessionPickerOverlay.java` | TUI overlay for session selection |

**Modified files:**

| File | Change |
|------|--------|
| `shared/session/SessionWriter.java` | Add `init()`, `readAllMessages()`; remove self-generated sessionId; add write callback |
| `GrimoStartupRunner.java` | Remove `@Bean SessionWriter`; add `@Bean SessionManager` will self-manage |
| `shared/session/SessionEventListener.java` | Inject SessionManager instead of SessionWriter |
| `ChatDispatcher.java` | Inject SessionManager instead of SessionWriter |
| `TuiAdapter.java` | Inject SessionManager; add `--continue`/`--resume` startup logic |
| `tui/TuiKeyHandler.java` | Inject SessionManager; intercept `/session-resume` |
| `tui/TuiEventBridge.java` | Add `on(SessionSwitchedEvent)` listener |
| `tui/view/StatusView.java` | Add session ID + resumed marker display |

---

### Task 1: SessionIndex and SessionMessage records

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/session/SessionIndex.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/session/SessionMessage.java`
- Test: `src/test/java/io/github/samzhu/grimo/shared/session/SessionIndexTest.java`

- [ ] **Step 1: Write SessionIndex record**

```java
package io.github.samzhu.grimo.shared.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/**
 * sessions-index.json 資料模型。
 *
 * 設計說明：
 * - 每個 project 一個 index 檔案，位於 ~/.grimo/projects/{encoded-cwd}/sessions-index.json
 * - SessionManager 即時更新（每次訊息寫入時同步）
 * - 使用 atomic write（temp file → rename）防 crash corruption
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionIndex(int version, List<Entry> sessions) {

    public static final int CURRENT_VERSION = 1;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
        String sessionId,
        Instant startedAt,
        Instant lastActiveAt,
        int messageCount,
        String firstUserMessage,
        String gitBranch,
        String agent,
        String model
    ) {}
}
```

- [ ] **Step 2: Write SessionMessage record**

```java
package io.github.samzhu.grimo.shared.session;

import java.time.Instant;

/**
 * JSONL 訊息讀取模型，供 session replay 用。
 *
 * 設計說明：
 * - 從 JSONL 檔案反序列化時使用
 * - TuiEventBridge replay 時依 type 決定渲染方式
 */
public record SessionMessage(
    String type,          // system, user, assistant, command, dispatch-entered, dispatch-completed
    String uuid,
    String parentUuid,
    Instant timestamp,
    String role,          // from message.role
    String content        // from message.content
) {}
```

- [ ] **Step 3: Write SessionIndex serialization test**

```java
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.session.SessionIndexTest" -x nativeTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/session/SessionIndex.java \
        src/main/java/io/github/samzhu/grimo/shared/session/SessionMessage.java \
        src/test/java/io/github/samzhu/grimo/shared/session/SessionIndexTest.java
git commit -m "feat(session): add SessionIndex and SessionMessage records"
```

---

### Task 2: Refactor SessionWriter — add init(), readAllMessages(), callback

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/session/SessionWriter.java`
- Modify: `src/test/java/io/github/samzhu/grimo/shared/session/SessionWriterTest.java`

- [ ] **Step 1: Write tests for new SessionWriter capabilities**

Add to `SessionWriterTest.java`:

```java
@Test
void initShouldSwitchTargetFile() throws Exception {
    var dataDir = tempDir.resolve("project-data");
    Files.createDirectories(dataDir);
    var writer = new SessionWriter(dataDir);

    // Init with a specific session
    writer.init("test1234", dataDir.resolve("test1234.jsonl"));
    writer.writeSystemMessage("/test", "1.0", "test");

    assertThat(writer.getSessionId()).isEqualTo("test1234");
    assertThat(writer.getSessionFile()).isEqualTo(dataDir.resolve("test1234.jsonl"));
    assertThat(writer.getSessionFile()).exists();
}

@Test
void readAllMessagesShouldParseJsonl() throws Exception {
    var dataDir = tempDir.resolve("project-data");
    Files.createDirectories(dataDir);
    var writer = new SessionWriter(dataDir);
    writer.init("read-test", dataDir.resolve("read-test.jsonl"));
    writer.writeSystemMessage("/test", "1.0", "test");
    writer.writeUserMessage("hello");
    writer.writeAssistantMessage("world");

    var messages = writer.readAllMessages(writer.getSessionFile());

    assertThat(messages).hasSize(3);
    assertThat(messages.get(0).type()).isEqualTo("system");
    assertThat(messages.get(1).type()).isEqualTo("user");
    assertThat(messages.get(1).content()).isEqualTo("hello");
    assertThat(messages.get(2).type()).isEqualTo("assistant");
    assertThat(messages.get(2).content()).isEqualTo("world");
}

@Test
void callbackShouldBeInvokedOnWrite() throws Exception {
    var dataDir = tempDir.resolve("project-data");
    Files.createDirectories(dataDir);
    var writer = new SessionWriter(dataDir);
    writer.init("cb-test", dataDir.resolve("cb-test.jsonl"));

    var types = new java.util.ArrayList<String>();
    var contents = new java.util.ArrayList<String>();
    writer.setWriteCallback((type, content) -> {
        types.add(type);
        contents.add(content);
    });

    writer.writeUserMessage("test input");
    writer.writeAssistantMessage("test reply");

    assertThat(types).containsExactly("user", "assistant");
    assertThat(contents.get(0)).isEqualTo("test input");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.session.SessionWriterTest" -x nativeTest`
Expected: FAIL — `init()`, `readAllMessages()`, `setWriteCallback()` do not exist yet

- [ ] **Step 3: Implement SessionWriter changes**

Changes to `SessionWriter.java`:
1. Make `sessionId` and `sessionFile` non-final (mutable via `init()`)
2. Add `init(String sessionId, Path sessionFile)` method
3. Add `readAllMessages(Path file)` method that parses JSONL
4. Add `WriteCallback` interface and `setWriteCallback()` method
5. Invoke callback at end of each write method
6. Keep constructor for backward compat (auto-generates sessionId as before)

```java
// New interface
public interface WriteCallback {
    void onWrite(String type, String content);
}

// New field
private WriteCallback writeCallback;

// New methods
public void setWriteCallback(WriteCallback callback) {
    this.writeCallback = callback;
}

public void init(String sessionId, Path sessionFile) {
    this.sessionId = sessionId;
    this.sessionFile = sessionFile;
    this.lastUuid = null;
    try {
        Files.createDirectories(sessionFile.getParent());
    } catch (IOException e) {
        // ignore
    }
}

/**
 * Resume 後恢復 lastUuid chain，確保後續寫入的 parentUuid 正確接續。
 */
public void setLastUuid(String lastUuid) {
    this.lastUuid = lastUuid;
}

public List<SessionMessage> readAllMessages(Path file) {
    var messages = new java.util.ArrayList<SessionMessage>();
    try {
        var lines = Files.readAllLines(file);
        for (String line : lines) {
            if (line.isBlank()) continue;
            var node = mapper.readTree(line);
            String type = node.path("type").asText();
            String uuid = node.path("uuid").asText(null);
            String parentUuid = node.path("parentUuid").asText(null);
            String timestamp = node.path("timestamp").asText(null);
            var msgNode = node.path("message");
            String role = msgNode.path("role").asText(null);
            String content = msgNode.path("content").asText(null);
            // For dispatch types, extract goal/summary as content
            if (content == null) {
                content = node.path("goal").asText(node.path("summary").asText(null));
            }
            messages.add(new SessionMessage(
                type, uuid, parentUuid,
                timestamp != null ? Instant.parse(timestamp) : null,
                role, content));
        }
    } catch (IOException e) {
        // Return what we have
    }
    return messages;
}
```

In each write method (writeUserMessage, writeAssistantMessage, writeCommandMessage, writeSystemMessage, writeDispatchEntered, writeDispatchCompleted), add at end:

```java
if (writeCallback != null) {
    writeCallback.onWrite("user", content);  // type and content vary per method
}
```

- [ ] **Step 4: Make sessionId and sessionFile mutable**

Change fields from `final` to non-final:
```java
private String sessionId;
private final Path dataDir;
private Path sessionFile;
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.session.SessionWriterTest" -x nativeTest`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/session/SessionWriter.java \
        src/test/java/io/github/samzhu/grimo/shared/session/SessionWriterTest.java
git commit -m "feat(session): add init/readAllMessages/writeCallback to SessionWriter"
```

---

### Task 3: SessionSwitchedEvent

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/SessionSwitchedEvent.java`

- [ ] **Step 1: Write SessionSwitchedEvent**

```java
package io.github.samzhu.grimo.shared.event;

import io.github.samzhu.grimo.shared.session.SessionMessage;
import java.util.List;

/**
 * Session 切換事件。
 *
 * 設計說明：
 * - resume 或 continue 時由 SessionManager publish
 * - TuiEventBridge 監聽後清空 contentView 並 replay 歷史訊息
 * - oldSessionId 為 null 表示啟動時的首次載入（非從既有 session 切換）
 */
public record SessionSwitchedEvent(
    String oldSessionId,
    String newSessionId,
    List<SessionMessage> messages
) {}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/event/SessionSwitchedEvent.java
git commit -m "feat(session): add SessionSwitchedEvent record"
```

---

### Task 4: SessionManager — core lifecycle logic

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/session/SessionManager.java`
- Test: `src/test/java/io/github/samzhu/grimo/shared/session/SessionManagerTest.java`

- [ ] **Step 1: Write SessionManager tests**

```java
package io.github.samzhu.grimo.shared.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SessionManagerTest {

    @TempDir Path tempDir;
    ApplicationEventPublisher publisher;
    SessionManager manager;

    @BeforeEach
    void setUp() {
        publisher = mock(ApplicationEventPublisher.class);
        manager = new SessionManager(tempDir, publisher, "claude", "opus");
    }

    @Test
    void startNewSessionShouldCreateJsonlAndIndex() throws Exception {
        manager.startNewSession("main", "/test", "1.0");

        assertThat(manager.getWriter().getSessionFile()).exists();
        var indexFile = tempDir.resolve("sessions-index.json");
        assertThat(indexFile).exists();

        var sessions = manager.listSessions();
        assertThat(sessions).hasSize(1);
        assertThat(sessions.getFirst().agent()).isEqualTo("claude");
        assertThat(sessions.getFirst().gitBranch()).isEqualTo("main");
    }

    @Test
    void onMessageWrittenShouldUpdateIndex() throws Exception {
        manager.startNewSession("main", "/test", "1.0");
        manager.onMessageWritten("user", "hello world");

        var sessions = manager.listSessions();
        assertThat(sessions.getFirst().messageCount()).isEqualTo(1);
        assertThat(sessions.getFirst().firstUserMessage()).isEqualTo("hello world");
    }

    @Test
    void firstUserMessageShouldTruncateAt80Chars() throws Exception {
        manager.startNewSession("main", "/test", "1.0");
        String longMsg = "a".repeat(100);
        manager.onMessageWritten("user", longMsg);

        var sessions = manager.listSessions();
        assertThat(sessions.getFirst().firstUserMessage()).hasSize(80);
    }

    @Test
    void resumeSessionShouldSwitchWriter() throws Exception {
        // Create first session
        manager.startNewSession("main", "/test", "1.0");
        String firstId = manager.getCurrentInfo().sessionId();
        manager.getWriter().writeUserMessage("first session msg");
        manager.onMessageWritten("user", "first session msg");

        // Create second session
        manager.startNewSession("main", "/test", "1.0");
        String secondId = manager.getCurrentInfo().sessionId();

        // Resume first
        manager.resumeSession(firstId);
        assertThat(manager.getCurrentInfo().sessionId()).isEqualTo(firstId);
        verify(publisher).publishEvent(any());
    }

    @Test
    void resumeNonExistentSessionShouldThrow() {
        manager.startNewSession("main", "/test", "1.0");
        assertThatThrownBy(() -> manager.resumeSession("nonexistent"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void continueLastSessionShouldResumeLatest() throws Exception {
        manager.startNewSession("main", "/test", "1.0");
        String firstId = manager.getCurrentInfo().sessionId();
        manager.getWriter().writeUserMessage("msg1");
        manager.onMessageWritten("user", "msg1");

        // Simulate restart: new manager with same dataDir
        var manager2 = new SessionManager(tempDir, publisher, "claude", "opus");
        boolean resumed = manager2.continueLastSession();

        assertThat(resumed).isTrue();
        assertThat(manager2.getCurrentInfo().sessionId()).isEqualTo(firstId);
    }

    @Test
    void continueLastWithNoHistoryShouldReturnFalse() {
        boolean resumed = manager.continueLastSession();
        assertThat(resumed).isFalse();
    }

    @Test
    void indexShouldSurviveCrashRecovery() throws Exception {
        manager.startNewSession("main", "/test", "1.0");
        manager.getWriter().writeUserMessage("test");
        manager.onMessageWritten("user", "test");

        // Delete index to simulate crash
        Files.deleteIfExists(tempDir.resolve("sessions-index.json"));

        // New manager should rebuild
        var manager2 = new SessionManager(tempDir, publisher, "claude", "opus");
        var sessions = manager2.listSessions();
        assertThat(sessions).hasSize(1);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.session.SessionManagerTest" -x nativeTest`
Expected: FAIL — SessionManager does not exist

- [ ] **Step 3: Write SessionManager implementation**

```java
package io.github.samzhu.grimo.shared.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.samzhu.grimo.shared.event.SessionSwitchedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Session 生命週期管理。
 *
 * 設計說明：
 * - 集中管理 session 建立/切換/索引，SessionWriter 只負責 JSONL I/O
 * - sessions-index.json 即時更新，每次訊息寫入時同步
 * - Resume 流程：flush 當前 → 載入指定 JSONL → publish event → TUI replay
 * - SessionWriter 單一 instance，resume 時透過 init() 切換目標檔案（mutable state）
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final String INDEX_FILE = "sessions-index.json";

    private final Path dataDir;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper mapper;
    private final SessionWriter writer;
    private final String defaultAgent;
    private final String defaultModel;

    private SessionIndex index;
    private SessionIndex.Entry currentEntry;
    private boolean resumed = false;

    public SessionManager(Path dataDir, ApplicationEventPublisher eventPublisher,
                          String defaultAgent, String defaultModel) {
        this.dataDir = dataDir;
        this.eventPublisher = eventPublisher;
        this.defaultAgent = defaultAgent;
        this.defaultModel = defaultModel;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.writer = new SessionWriter(dataDir);
        this.writer.setWriteCallback(this::onMessageWritten);
        this.index = loadOrRebuildIndex();
    }

    // --- Public API ---

    /**
     * 建立新 session：產生 ID、初始化 JSONL、寫入 system message、更新 index。
     *
     * @param gitBranch 當前 git branch
     * @param cwd       當前工作目錄（寫入 system message）
     * @param version   Grimo 版本（寫入 system message）
     */
    public void startNewSession(String gitBranch, String cwd, String version) {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        Path sessionFile = dataDir.resolve(sessionId + ".jsonl");
        writer.init(sessionId, sessionFile);
        writer.writeSystemMessage(cwd, version, "Grimo TUI session");

        var now = Instant.now();
        currentEntry = new SessionIndex.Entry(
                sessionId, now, now, 0, null,
                gitBranch, defaultAgent, defaultModel);
        resumed = false;

        var sessions = new ArrayList<>(index.sessions());
        sessions.addFirst(currentEntry);
        index = new SessionIndex(SessionIndex.CURRENT_VERSION, sessions);
        saveIndex();
    }

    public void resumeSession(String sessionId) {
        var entry = findEntry(sessionId);
        if (entry == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }

        String oldId = currentEntry != null ? currentEntry.sessionId() : null;
        Path sessionFile = dataDir.resolve(sessionId + ".jsonl");
        writer.init(sessionId, sessionFile);
        currentEntry = entry;
        resumed = true;

        // Update lastActiveAt
        updateCurrentEntry(entry.messageCount(), entry.firstUserMessage());

        var messages = writer.readAllMessages(sessionFile);
        // Restore lastUuid chain so subsequent writes have correct parentUuid
        if (!messages.isEmpty()) {
            writer.setLastUuid(messages.getLast().uuid());
        }

        eventPublisher.publishEvent(new SessionSwitchedEvent(oldId, sessionId, messages));
    }

    public boolean continueLastSession() {
        if (index.sessions().isEmpty()) return false;
        var latest = index.sessions().stream()
                .max(Comparator.comparing(SessionIndex.Entry::lastActiveAt))
                .orElse(null);
        if (latest == null) return false;
        resumeSession(latest.sessionId());
        return true;
    }

    public List<SessionIndex.Entry> listSessions() {
        return Collections.unmodifiableList(index.sessions());
    }

    public record SessionInfo(
        String sessionId, Instant startedAt, Instant lastActiveAt,
        int messageCount, String agent, String model, boolean resumed
    ) {}

    public SessionInfo getCurrentInfo() {
        if (currentEntry == null) return null;
        return new SessionInfo(
                currentEntry.sessionId(), currentEntry.startedAt(),
                currentEntry.lastActiveAt(), currentEntry.messageCount(),
                currentEntry.agent(), currentEntry.model(), resumed);
    }

    public SessionWriter getWriter() {
        return writer;
    }

    public boolean isResumed() {
        return resumed;
    }

    // --- Write callback (invoked by SessionWriter) ---

    public void onMessageWritten(String type, String content) {
        if (currentEntry == null) return;

        int newCount = currentEntry.messageCount() + 1;
        String firstMsg = currentEntry.firstUserMessage();
        if ("user".equals(type) && firstMsg == null && content != null) {
            firstMsg = content.length() > 80 ? content.substring(0, 80) : content;
        }
        updateCurrentEntry(newCount, firstMsg);
        saveIndex();
    }

    // --- Internal ---

    private void updateCurrentEntry(int messageCount, String firstUserMessage) {
        currentEntry = new SessionIndex.Entry(
                currentEntry.sessionId(), currentEntry.startedAt(), Instant.now(),
                messageCount, firstUserMessage,
                currentEntry.gitBranch(), currentEntry.agent(), currentEntry.model());
        var sessions = index.sessions().stream()
                .map(e -> e.sessionId().equals(currentEntry.sessionId()) ? currentEntry : e)
                .collect(Collectors.toCollection(ArrayList::new));
        index = new SessionIndex(SessionIndex.CURRENT_VERSION, sessions);
    }

    private SessionIndex.Entry findEntry(String sessionId) {
        return index.sessions().stream()
                .filter(e -> e.sessionId().equals(sessionId))
                .findFirst().orElse(null);
    }

    private void saveIndex() {
        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(index);
            Path tmp = dataDir.resolve(INDEX_FILE + ".tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, dataDir.resolve(INDEX_FILE), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Failed to save sessions-index.json: {}", e.getMessage());
        }
    }

    private SessionIndex loadOrRebuildIndex() {
        Path indexFile = dataDir.resolve(INDEX_FILE);
        if (Files.exists(indexFile)) {
            try {
                return mapper.readValue(indexFile.toFile(), SessionIndex.class);
            } catch (IOException e) {
                log.warn("Failed to read sessions-index.json, rebuilding: {}", e.getMessage());
            }
        }
        return rebuildIndex();
    }

    private SessionIndex rebuildIndex() {
        var entries = new ArrayList<SessionIndex.Entry>();
        try (var stream = Files.list(dataDir)) {
            stream.filter(p -> p.toString().endsWith(".jsonl")).forEach(file -> {
                try {
                    var messages = writer.readAllMessages(file);
                    if (messages.isEmpty()) return;

                    String sessionId = file.getFileName().toString().replace(".jsonl", "");
                    Instant startedAt = messages.getFirst().timestamp();
                    Instant lastActiveAt = messages.getLast().timestamp();
                    int count = messages.size();
                    String firstUser = messages.stream()
                            .filter(m -> "user".equals(m.type()))
                            .map(SessionMessage::content)
                            .findFirst().orElse(null);
                    if (firstUser != null && firstUser.length() > 80) {
                        firstUser = firstUser.substring(0, 80);
                    }

                    entries.add(new SessionIndex.Entry(
                            sessionId, startedAt, lastActiveAt, count,
                            firstUser, null, null, null));
                } catch (Exception e) {
                    log.warn("Failed to read session file {}: {}", file, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to scan session directory: {}", e.getMessage());
        }
        entries.sort(Comparator.comparing(SessionIndex.Entry::lastActiveAt, Comparator.nullsLast(Comparator.reverseOrder())));
        var index = new SessionIndex(SessionIndex.CURRENT_VERSION, entries);
        // Save rebuilt index
        this.index = index;
        saveIndex();
        return index;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.session.SessionManagerTest" -x nativeTest`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/session/SessionManager.java \
        src/test/java/io/github/samzhu/grimo/shared/session/SessionManagerTest.java
git commit -m "feat(session): add SessionManager with lifecycle, index, resume logic"
```

---

### Task 5: Wire SessionManager as Spring bean — replace SessionWriter injection

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`
- Modify: `src/main/java/io/github/samzhu/grimo/shared/session/SessionEventListener.java`
- Modify: `src/main/java/io/github/samzhu/grimo/ChatDispatcher.java`
- Modify: `src/main/java/io/github/samzhu/grimo/tui/TuiKeyHandler.java`
- Modify: `src/main/java/io/github/samzhu/grimo/TuiAdapter.java`

- [ ] **Step 1: Add SessionManager @Bean in GrimoStartupRunner, remove SessionWriter @Bean**

In `GrimoStartupRunner.java`:
- Remove the `@Bean SessionWriter sessionWriter(ProjectContext projectContext)` method (lines 70-73)
- Add:

```java
@Bean
SessionManager sessionManager(ProjectContext projectContext,
                               ApplicationEventPublisher eventPublisher,
                               GrimoConfig grimoConfig,
                               GrimoProperties grimoProperties) {
    String agent = grimoConfig.getDefaultAgent();
    if (agent == null) agent = "unknown";
    String model = grimoConfig.getDefaultModel();
    if (model == null) model = grimoProperties.getDefaults().getOrDefault(agent, "unknown");
    return new SessionManager(projectContext.dataDir(), eventPublisher, agent, model);
}
```

- [ ] **Step 2: Update SessionEventListener to use SessionManager**

In `SessionEventListener.java`:
- Change constructor to accept `SessionManager` instead of `SessionWriter`
- Get writer via `sessionManager.getWriter()`

```java
@Component
public class SessionEventListener {

    private final SessionManager sessionManager;

    public SessionEventListener(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @EventListener
    public void on(AgentCallRecordedEvent event) {
        var writer = sessionManager.getWriter();
        writer.writeUserMessage(event.userGoal());
        writer.writeAssistantMessage(event.agentResult());
    }

    @EventListener
    public void on(DevModeEnteredEvent event) {
        sessionManager.getWriter().writeDispatchEntered(
            event.taskId(), event.agent(), event.model(),
            event.tier(), event.branchName(), event.goal());
    }

    @EventListener
    public void on(DevModeCompletedEvent event) {
        sessionManager.getWriter().writeDispatchCompleted(
            event.taskId(), event.agent(), event.model(),
            event.tier(), event.goal(),
            event.executionMode(), event.workDir(),
            event.branchName(), event.baseSha(), null,
            event.hasChanges(), event.commitCount(),
            event.diffStat(), event.durationMs(),
            event.result(), event.externalSessionPath());
    }
}
```

- [ ] **Step 3: Update ChatDispatcher to use SessionManager**

In `ChatDispatcher.java`:
- Change constructor parameter from `SessionWriter sessionWriter` to `SessionManager sessionManager`
- Store `sessionManager` field
- Replace all `sessionWriter.writeXxx()` calls with `sessionManager.getWriter().writeXxx()`
- Update import

- [ ] **Step 4: Update TuiKeyHandler to accept SessionManager**

In `TuiKeyHandler.java`:
- Change constructor parameter from `SessionWriter sessionWriter` to `SessionManager sessionManager`
- Store `sessionManager` field, derive `sessionWriter` via `sessionManager.getWriter()`
- All existing `sessionWriter.` calls remain unchanged (local reference)

- [ ] **Step 5: Update TuiAdapter to use SessionManager**

In `TuiAdapter.java`:
- Change constructor parameter from `SessionWriter sessionWriter` to `SessionManager sessionManager`
- Store `sessionManager` field
- In `run()`, replace `sessionWriter.writeSystemMessage(...)` with `sessionManager.startNewSession(gitBranch, cwd, version)` where gitBranch comes from `gitHelper.getCurrentBranch()` (or "unknown"), cwd from `projectContext.path().toString()`, version from package info
- Pass `sessionManager` to `TuiKeyHandler` constructor instead of `sessionWriter`
- In `processInput()`, replace `sessionWriter.getSessionId()` with `sessionManager.getCurrentInfo().sessionId()`

- [ ] **Step 6: Run full tests to verify wiring is correct**

Run: `./gradlew test -x nativeTest`
Expected: ALL PASS (existing tests + new tests)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java \
        src/main/java/io/github/samzhu/grimo/shared/session/SessionEventListener.java \
        src/main/java/io/github/samzhu/grimo/ChatDispatcher.java \
        src/main/java/io/github/samzhu/grimo/tui/TuiKeyHandler.java \
        src/main/java/io/github/samzhu/grimo/TuiAdapter.java
git commit -m "refactor(session): wire SessionManager bean, replace direct SessionWriter injection"
```

---

### Task 6: SessionCommands — /session-info and /session-resume registration

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/command/SessionCommands.java`
- Test: `src/test/java/io/github/samzhu/grimo/command/SessionCommandsTest.java`

- [ ] **Step 1: Write SessionCommands test**

```java
package io.github.samzhu.grimo.command;

import io.github.samzhu.grimo.shared.session.SessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SessionCommandsTest {

    @TempDir Path tempDir;

    @Test
    void sessionInfoShouldReturnFormattedInfo() {
        var publisher = mock(ApplicationEventPublisher.class);
        var manager = new SessionManager(tempDir, publisher, "claude", "opus");
        manager.startNewSession("main", "/test", "1.0");
        manager.onMessageWritten("user", "test");

        var commands = new SessionCommands(manager);
        String result = commands.info();

        assertThat(result).contains(manager.getCurrentInfo().sessionId());
        assertThat(result).contains("claude");
        assertThat(result).contains("1"); // messageCount
    }

    @Test
    void sessionInfoWithNoSessionShouldReturnMessage() {
        var publisher = mock(ApplicationEventPublisher.class);
        var manager = new SessionManager(tempDir, publisher, "claude", "opus");

        var commands = new SessionCommands(manager);
        String result = commands.info();

        assertThat(result).contains("No active session");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.samzhu.grimo.command.SessionCommandsTest" -x nativeTest`
Expected: FAIL

- [ ] **Step 3: Write SessionCommands**

```java
package io.github.samzhu.grimo.command;

import io.github.samzhu.grimo.shared.session.SessionManager;
import org.springframework.shell.command.annotation.Command;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Session 管理指令。
 *
 * 設計說明：
 * - /session-resume 是 TUI 專屬攔截（在 TuiKeyHandler 處理，開 overlay），不在此註冊
 * - /session-info 走正常指令管線（InputPort → CommandDispatcher → 這裡）
 * - 放在 command/ package 而非 shared/session/，遵循 shared/ 不依賴功能模組規則
 */
@Component
public class SessionCommands {

    private final SessionManager sessionManager;

    public SessionCommands(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Command(name = "session-info", description = "Show current session info")
    public String info() {
        var info = sessionManager.getCurrentInfo();
        if (info == null) return "No active session.";

        String elapsed = formatDuration(Duration.between(info.startedAt(), Instant.now()));
        return String.format("""
                Session: %s%s
                Started: %s (%s ago)
                Messages: %d
                Agent: %s / %s""",
                info.sessionId(),
                info.resumed() ? " ↩" : "",
                info.startedAt(), elapsed,
                info.messageCount(),
                info.agent(), info.model());
    }

    private String formatDuration(Duration d) {
        if (d.toDays() > 0) return d.toDays() + "d";
        if (d.toHours() > 0) return d.toHours() + "h";
        if (d.toMinutes() > 0) return d.toMinutes() + "m";
        return d.toSeconds() + "s";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.samzhu.grimo.command.SessionCommandsTest" -x nativeTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/command/SessionCommands.java \
        src/test/java/io/github/samzhu/grimo/command/SessionCommandsTest.java
git commit -m "feat(session): add /session-info command"
```

---

### Task 7: SessionPickerOverlay

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/tui/overlay/SessionPickerOverlay.java`
- Test: `src/test/java/io/github/samzhu/grimo/tui/overlay/SessionPickerOverlayTest.java`

- [ ] **Step 1: Write SessionPickerOverlay test**

```java
package io.github.samzhu.grimo.tui.overlay;

import io.github.samzhu.grimo.shared.session.SessionIndex;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SessionPickerOverlayTest {

    @Test
    void renderShouldShowSessionEntries() {
        var entries = List.of(
            new SessionIndex.Entry("a3f1b2c4",
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600),
                24, "幫我重構 auth module", "main", "claude", "opus"),
            new SessionIndex.Entry("b7e2f190",
                Instant.now().minusSeconds(86400), Instant.now().minusSeconds(43200),
                12, "設計 session", "main", "gemini", "flash")
        );
        var picker = new SessionPickerOverlay(entries);
        var lines = picker.render(60);

        assertThat(lines).isNotEmpty();
        // Should contain session IDs
        var text = lines.stream()
                .map(Object::toString).reduce("", String::concat);
        assertThat(text).contains("a3f1b2c4");
        assertThat(text).contains("b7e2f190");
    }

    @Test
    void renderEmptyShouldShowNoSessionMessage() {
        var picker = new SessionPickerOverlay(List.of());
        var lines = picker.render(60);

        var text = lines.stream()
                .map(Object::toString).reduce("", String::concat);
        assertThat(text).contains("No previous sessions");
    }

    @Test
    void moveDownShouldChangeSelection() {
        var entries = List.of(
            new SessionIndex.Entry("aaa", Instant.now(), Instant.now(), 1, "a", "main", "c", "o"),
            new SessionIndex.Entry("bbb", Instant.now(), Instant.now(), 2, "b", "main", "c", "o")
        );
        var picker = new SessionPickerOverlay(entries);
        assertThat(picker.getSelectedIndex()).isEqualTo(0);
        picker.moveDown();
        assertThat(picker.getSelectedIndex()).isEqualTo(1);
        picker.moveDown(); // should not go past last
        assertThat(picker.getSelectedIndex()).isEqualTo(1);
    }

    @Test
    void getSelectedEntryShouldReturnCorrectEntry() {
        var entry = new SessionIndex.Entry("aaa", Instant.now(), Instant.now(), 1, "a", "main", "c", "o");
        var picker = new SessionPickerOverlay(List.of(entry));
        assertThat(picker.getSelectedEntry().sessionId()).isEqualTo("aaa");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.samzhu.grimo.tui.overlay.SessionPickerOverlayTest" -x nativeTest`
Expected: FAIL

- [ ] **Step 3: Write SessionPickerOverlay**

Follow McpPanel pattern — pure data + `render()` producing `List<AttributedString>`:

```java
package io.github.samzhu.grimo.tui.overlay;

import io.github.samzhu.grimo.shared.session.SessionIndex;
import io.github.samzhu.grimo.tui.core.DisplayWidth;
import io.github.samzhu.grimo.tui.core.Renderable;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Session 選擇 overlay：顯示歷史 session 列表供使用者選取 resume。
 *
 * 設計說明：
 * - 跟隨 McpPanel/SlashMenu pattern（純資料 + render()）
 * - 不負責按鍵處理（由 TuiKeyHandler 處理）
 * - 每筆 entry 兩行：(1) ID 時間 訊息數 branch agent/model (2) firstUserMessage
 */
public class SessionPickerOverlay implements Renderable {

    private static final int BRAND_COLOR = 67;
    private static final AttributedStyle BRAND_STYLE = AttributedStyle.DEFAULT.foreground(BRAND_COLOR);
    private static final AttributedStyle DIM_STYLE = AttributedStyle.DEFAULT.foreground(245);
    private static final AttributedStyle SELECTED_STYLE = AttributedStyle.DEFAULT.foreground(BRAND_COLOR).bold();
    private static final AttributedStyle HINT_STYLE = AttributedStyle.DEFAULT.foreground(245);

    private final List<SessionIndex.Entry> entries;
    private int selectedIndex = 0;

    public SessionPickerOverlay(List<SessionIndex.Entry> entries) {
        this.entries = entries;
    }

    public void moveUp() {
        if (selectedIndex > 0) selectedIndex--;
    }

    public void moveDown() {
        if (selectedIndex < entries.size() - 1) selectedIndex++;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public SessionIndex.Entry getSelectedEntry() {
        if (entries.isEmpty()) return null;
        return entries.get(selectedIndex);
    }

    @Override
    public List<AttributedString> render(int cols) {
        var lines = new ArrayList<AttributedString>();

        // Title
        lines.add(new AttributedString(
                DisplayWidth.padRight("Session Resume", cols), BRAND_STYLE));
        lines.add(new AttributedString(DisplayWidth.padRight("", cols)));

        if (entries.isEmpty()) {
            lines.add(new AttributedString(
                    DisplayWidth.padRight("  No previous sessions found.", cols), DIM_STYLE));
            lines.add(new AttributedString(DisplayWidth.padRight("", cols)));
            lines.add(new AttributedString(
                    DisplayWidth.padRight("  Esc close", cols), HINT_STYLE));
            return lines;
        }

        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            boolean selected = i == selectedIndex;
            var style = selected ? SELECTED_STYLE : DIM_STYLE;
            String marker = selected ? "> " : "  ";

            // Line 1: ID  time  msgs  branch  agent/model
            String timeAgo = formatTimeAgo(entry.lastActiveAt());
            String line1 = String.format("%s%s  %s  %d msgs  %s  %s/%s",
                    marker, entry.sessionId(), timeAgo, entry.messageCount(),
                    entry.gitBranch() != null ? entry.gitBranch() : "",
                    entry.agent() != null ? entry.agent() : "",
                    entry.model() != null ? entry.model() : "");
            lines.add(new AttributedString(
                    DisplayWidth.padRight(DisplayWidth.truncate(line1, cols), cols), style));

            // Line 2: firstUserMessage
            String msg = entry.firstUserMessage() != null ? entry.firstUserMessage() : "";
            String line2 = "    " + msg;
            lines.add(new AttributedString(
                    DisplayWidth.padRight(DisplayWidth.truncate(line2, cols), cols),
                    selected ? SELECTED_STYLE : DIM_STYLE));

            // Blank separator
            if (i < entries.size() - 1) {
                lines.add(new AttributedString(DisplayWidth.padRight("", cols)));
            }
        }

        lines.add(new AttributedString(DisplayWidth.padRight("", cols)));
        lines.add(new AttributedString(
                DisplayWidth.padRight("  ↑↓ navigate  Enter select  Esc cancel", cols), HINT_STYLE));

        return lines;
    }

    private String formatTimeAgo(Instant time) {
        if (time == null) return "";
        Duration d = Duration.between(time, Instant.now());
        if (d.toDays() > 0) return d.toDays() + "d ago";
        if (d.toHours() > 0) return d.toHours() + "h ago";
        if (d.toMinutes() > 0) return d.toMinutes() + "m ago";
        return "now";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.samzhu.grimo.tui.overlay.SessionPickerOverlayTest" -x nativeTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/tui/overlay/SessionPickerOverlay.java \
        src/test/java/io/github/samzhu/grimo/tui/overlay/SessionPickerOverlayTest.java
git commit -m "feat(session): add SessionPickerOverlay for interactive session selection"
```

---

### Task 8: TUI integration — TuiKeyHandler intercept, TuiEventBridge replay, StatusView

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/tui/TuiKeyHandler.java`
- Modify: `src/main/java/io/github/samzhu/grimo/tui/TuiEventBridge.java`
- Modify: `src/main/java/io/github/samzhu/grimo/tui/view/StatusView.java`
- Modify: `src/main/java/io/github/samzhu/grimo/TuiAdapter.java`

- [ ] **Step 1: Add /session-resume intercept in TuiAdapter.processInput()**

In `TuiAdapter.processInput()`, add alongside existing intercepts:

```java
if (text.equals("/session-resume")) { showSessionPicker(); return; }
```

Add `showSessionPicker()` method:

```java
private void showSessionPicker() {
    var entries = sessionManager.listSessions();
    // Exclude current session from picker
    var currentId = sessionManager.getCurrentInfo() != null
            ? sessionManager.getCurrentInfo().sessionId() : null;
    var filtered = entries.stream()
            .filter(e -> !e.sessionId().equals(currentId))
            .toList();

    var picker = new SessionPickerOverlay(filtered);
    tuiKeyHandler.setActiveSelectOverlay(picker);
    screen.setSelectOverlay(picker);
    eventLoop.setDirty();
}
```

- [ ] **Step 2: Add SessionPickerOverlay handling in TuiKeyHandler.handleSelectOverlayInput()**

Add `instanceof SessionPickerOverlay` branch:

```java
} else if (activeSelectOverlay instanceof SessionPickerOverlay sessionPicker) {
    switch (operation) {
        case EventLoop.OP_UP -> sessionPicker.moveUp();
        case EventLoop.OP_DOWN -> sessionPicker.moveDown();
        case EventLoop.OP_ENTER -> {
            var selected = sessionPicker.getSelectedEntry();
            screen.clearSelectOverlay();
            activeSelectOverlay = null;
            if (selected != null) {
                sessionManager.resumeSession(selected.sessionId());
            }
        }
        case EventLoop.OP_ESC, EventLoop.OP_CTRL_C -> {
            screen.clearSelectOverlay();
            activeSelectOverlay = null;
        }
    }
}
```

Add import for `SessionPickerOverlay` and store `sessionManager` reference (already done in Task 5).

- [ ] **Step 3: Add SessionSwitchedEvent listener in TuiEventBridge**

Uses the shared `replayMessages()` utility (extracted in Step 6). See Step 6 for the full implementation. The listener simply calls:

```java
@EventListener
void on(SessionSwitchedEvent event) {
    if (contentView == null) return;
    contentView.clear();
    replayMessages(event.messages(), contentView);
    refreshStatusBar();
    setDirty.run();
}
```

Add import for `SessionSwitchedEvent` and `SessionMessage`.

- [ ] **Step 4: Update StatusView to show session ID and ↩ marker**

In `TuiEventBridge.refreshStatusBar()`, prepend session info:

```java
private void refreshStatusBar() {
    // ... existing agent/model/project resolution ...

    // Prepend session ID if available
    String sessionPrefix = "";
    if (sessionManager != null && sessionManager.getCurrentInfo() != null) {
        var info = sessionManager.getCurrentInfo();
        sessionPrefix = "[" + info.sessionId()
                + (info.resumed() ? " ↩" : "") + "] ";
    }

    String newStatus = sessionPrefix + agentId + " · " + model + " │ " + projectPath
            + " │ " + (int) agentCount + " agent · " + skillCount + " skill · "
            + mcpCount + " mcp · " + taskCount + " task";
    this.originalStatusText = newStatus;
    statusView.setStatusText(newStatus);
}
```

Inject `SessionManager` into `TuiEventBridge` constructor.

- [ ] **Step 5: Add --continue and --resume startup args in TuiAdapter.run()**

At the beginning of `TuiAdapter.run()`, after environment prep and before TUI construction:

```java
// === Session startup mode ===
String gitBranch = "unknown";
try { gitBranch = gitHelper.getCurrentBranch(); } catch (Exception e) { /* ignore */ }
String cwd = projectContext.path().toString();

boolean hasResume = args.containsOption("resume");
boolean hasContinue = args.containsOption("continue");

if (hasResume) {
    var resumeValues = args.getOptionValues("resume");
    if (resumeValues != null && !resumeValues.isEmpty() && !resumeValues.getFirst().isEmpty()) {
        // --resume <id>
        String id = resumeValues.getFirst();
        try {
            sessionManager.resumeSession(id);
        } catch (IllegalArgumentException e) {
            System.err.println("Session not found: " + id);
            System.exit(1);
        }
    } else {
        // --resume (no id) → terminal picker before TUI
        var sessions = sessionManager.listSessions();
        if (sessions.isEmpty()) {
            sessionManager.startNewSession(gitBranch, cwd, version);
        } else {
            // Simple JLine picker (pre-TUI, normal mode)
            var selected = showTerminalSessionPicker(sessions);
            if (selected != null) {
                sessionManager.resumeSession(selected.sessionId());
            } else {
                sessionManager.startNewSession(gitBranch, cwd, version);
            }
        }
    }
} else if (hasContinue) {
    boolean resumed = sessionManager.continueLastSession();
    if (!resumed) {
        sessionManager.startNewSession(gitBranch, cwd, version);
    }
} else {
    sessionManager.startNewSession(gitBranch, cwd, version);
}
```

Add `showTerminalSessionPicker()` method using JLine's LineReader:

```java
private SessionIndex.Entry showTerminalSessionPicker(List<SessionIndex.Entry> sessions) {
    System.out.println("? Select a session to resume:");
    for (int i = 0; i < sessions.size(); i++) {
        var e = sessions.get(i);
        String timeAgo = formatTimeAgo(e.lastActiveAt());
        String msg = e.firstUserMessage() != null ? e.firstUserMessage() : "";
        if (msg.length() > 50) msg = msg.substring(0, 50) + "...";
        System.out.printf("  %s%d) %s  %s  %d msgs  %s%n",
                i == 0 ? "> " : "  ", i + 1, e.sessionId(), timeAgo, e.messageCount(), msg);
    }
    System.out.print("Enter number (or press Enter to cancel): ");
    try {
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        String line = reader.readLine();
        if (line != null && !line.isBlank()) {
            int idx = Integer.parseInt(line.trim()) - 1;
            if (idx >= 0 && idx < sessions.size()) return sessions.get(idx);
        }
    } catch (Exception e) { /* ignore */ }
    return null;
}

private String formatTimeAgo(java.time.Instant time) {
    if (time == null) return "";
    var d = java.time.Duration.between(time, java.time.Instant.now());
    if (d.toDays() > 0) return d.toDays() + "d ago";
    if (d.toHours() > 0) return d.toHours() + "h ago";
    if (d.toMinutes() > 0) return d.toMinutes() + "m ago";
    return "now";
}
```

- [ ] **Step 6: Extract shared replay utility in TuiEventBridge**

To avoid duplicating replay logic between startup (TuiAdapter) and runtime (SessionSwitchedEvent), extract a `public static` utility method in `TuiEventBridge`:

```java
/**
 * 將 session 歷史訊息 replay 到 contentView。
 * 共用方法：startup --continue/--resume 和 TUI 內 /session-resume 都呼叫此方法。
 */
public static void replayMessages(List<SessionMessage> messages, ContentView contentView) {
    for (var msg : messages) {
        switch (msg.type()) {
            case "user" -> contentView.appendUserInput(msg.content());
            case "assistant" -> contentView.appendAiReply(msg.content());
            case "command" -> contentView.appendCommandOutput(msg.content());
            case "dispatch-entered" -> {
                if (msg.content() != null)
                    contentView.appendLine(new AttributedString("⚙ Dev dispatch: " + msg.content(),
                            AttributedStyle.DEFAULT.foreground(245)));
            }
            case "dispatch-completed" -> {
                if (msg.content() != null)
                    contentView.appendLine(new AttributedString("✓ Dispatch done: " + msg.content(),
                            AttributedStyle.DEFAULT.foreground(245)));
            }
            // system → skip
        }
    }
}
```

In `TuiEventBridge.on(SessionSwitchedEvent)`, call the shared method:

```java
@EventListener
void on(SessionSwitchedEvent event) {
    if (contentView == null) return;
    contentView.clear();
    replayMessages(event.messages(), contentView);
    refreshStatusBar();
    setDirty.run();
}
```

In `TuiAdapter.run()`, after TUI construction (contentView exists), if resumed at startup:

```java
if (sessionManager.isResumed()) {
    var messages = sessionManager.getWriter().readAllMessages(
            sessionManager.getWriter().getSessionFile());
    TuiEventBridge.replayMessages(messages, contentView);
}
```

Remove the old `sessionWriter.writeSystemMessage(...)` call since `startNewSession()` now handles that.

- [ ] **Step 7: Run full test suite**

Run: `./gradlew test -x nativeTest`
Expected: ALL PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/TuiAdapter.java \
        src/main/java/io/github/samzhu/grimo/tui/TuiKeyHandler.java \
        src/main/java/io/github/samzhu/grimo/tui/TuiEventBridge.java \
        src/main/java/io/github/samzhu/grimo/tui/view/StatusView.java
git commit -m "feat(session): TUI integration — picker, replay, status bar, startup args"
```

---

### Task 9: Glossary and docs update

**Files:**
- Modify: `docs/glossary.md`

- [ ] **Step 1: Add glossary entries**

Append to `docs/glossary.md`:

```markdown
| SessionManager | Session 生命週期管理者。建立/切換/列出 session，維護 sessions-index.json，提供 list/resume/info API |
| sessions-index.json | Per-project session 索引檔，位於 `~/.grimo/projects/{encoded-cwd}/sessions-index.json`。記錄所有 session 的 ID、時間、訊息數、首句、agent、branch |
| SessionPickerOverlay | TUI overlay，互動式選擇歷史 session 進行 resume。跟隨 McpPanel/SlashMenu pattern |
```

- [ ] **Step 2: Commit**

```bash
git add docs/glossary.md
git commit -m "docs: add session management glossary entries"
```

---

### Task 10: Integration smoke test

**Files:**
- No new files, manual verification

- [ ] **Step 1: Build the project**

Run: `./gradlew build -x nativeCompile -x nativeTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run the app and verify new session**

Run: `./gradlew bootRun`
Expected: TUI starts, status bar shows `[xxxxxxxx] agent · model │ ...`

- [ ] **Step 3: Verify /session-info**

Type `/session-info` in TUI.
Expected: Shows session ID, start time, message count, agent/model

- [ ] **Step 4: Exit and verify sessions-index.json**

Type `/exit`. Check `~/.grimo/projects/{encoded-cwd}/sessions-index.json` exists with valid JSON.

- [ ] **Step 5: Verify --continue**

Run: `./gradlew bootRun --args='--continue'`
Expected: Loads last session, replays history to screen, status bar shows `[id ↩]`

- [ ] **Step 6: Verify /session-resume overlay**

Type `/session-resume` in TUI.
Expected: Overlay appears with session list, ↑↓ navigate, Enter selects, Esc cancels
