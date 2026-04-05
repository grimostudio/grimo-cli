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
