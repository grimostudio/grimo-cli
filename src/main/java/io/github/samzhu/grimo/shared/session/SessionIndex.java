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
