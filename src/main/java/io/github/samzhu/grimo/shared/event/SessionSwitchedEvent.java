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
