package io.github.samzhu.grimo.shared.event;

import java.util.List;

/**
 * 統一訊息管線的出站事件：MessageRouter 處理完成後發布，TUI 與 Channel adapters 均監聽此事件。
 *
 * 設計說明：
 * - channelType / conversationId / attachments：保留舊欄位，供 ChannelEventListener 路由使用
 * - targetAdapter：回傳目標 adapter 識別碼（"tui" | "line" | "telegram" | "discord"）
 *   null 表示廣播（broadcast）— 由所有適用的 adapter 處理
 *   ChannelEventListener 同時支援 targetAdapter 與 channelType 路由（後者為舊行為）
 */
public record OutgoingMessageEvent(
    String channelType,
    String conversationId,
    String text,
    List<Attachment> attachments,
    String targetAdapter      // nullable; "tui" | "line" | "telegram" | "discord"; null = broadcast
) {}
