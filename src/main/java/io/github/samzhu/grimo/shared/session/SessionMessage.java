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
