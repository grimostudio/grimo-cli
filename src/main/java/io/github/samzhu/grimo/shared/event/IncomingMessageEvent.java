package io.github.samzhu.grimo.shared.event;

import java.time.Instant;
import java.util.List;

/**
 * 統一訊息管線的入站事件：TUI、Channel（LINE、Telegram、Discord）均透過此事件進入處理流程。
 *
 * 設計說明：
 * - channelType / channelUserId / conversationId：保留舊欄位，供 Channel 模組使用
 * - sourceAdapter：訊息來源 adapter 識別碼（"tui" | "line" | "telegram" | "discord"）
 *   用於 MessageRouter 判斷回傳目標、TUI 專屬攔截等
 * - sessionId：對應 SessionWriter 的 sessionId，可為 null（非 TUI 來源時）
 */
public record IncomingMessageEvent(
    String channelType,       // "telegram" | "line" | "tui"
    String channelUserId,
    String conversationId,
    String text,
    List<Attachment> attachments,
    Instant timestamp,
    String sourceAdapter,     // "tui" | "line" | "telegram" | "discord"
    String sessionId          // nullable; TUI session id from SessionWriter
) {}
