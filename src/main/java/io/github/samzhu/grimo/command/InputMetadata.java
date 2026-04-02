package io.github.samzhu.grimo.command;

/**
 * Adapter 附帶的上下文資訊。
 * 只帶 Core 需要的資訊。不帶 channel 回覆細節（封在 ResponseCallback）。
 */
public record InputMetadata(
    String source,      // "tui", "line", "discord"
    String userId,      // 使用者識別
    String sessionId    // 對話 session ID
) {
    public static InputMetadata tui(String sessionId) {
        return new InputMetadata("tui", null, sessionId);
    }
    public static InputMetadata line(String userId, String sessionId) {
        return new InputMetadata("line", userId, sessionId);
    }
    public static InputMetadata discord(String userId, String sessionId) {
        return new InputMetadata("discord", userId, sessionId);
    }
}
