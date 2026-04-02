package io.github.samzhu.grimo.command;

import java.util.List;

/**
 * Driving Port：使用者輸入入口。
 *
 * 設計說明（六角架構）：
 * - 單一 Port — Adapter 不判斷 command vs chat，全部送進來
 * - Core 內部路由：/ 開頭 → CommandDispatcher、@ 開頭 → agent mention、其他 → AI 對話
 * - ResponseCallback 是 Adapter 的 closure — 封裝 channel-specific 回覆機制
 *   TUI: contentView.append()、LINE: lineApi.reply(replyToken)、Discord: channelApi.send(channelId)
 * - Core 完全不知道 replyToken、channelId 等 channel 細節
 */
public interface InputPort {

    /**
     * Adapter 的回應 callback。區分 success / error 讓 Adapter 能正確渲染。
     * TUI: onSuccess → appendAiReply、onError → appendError
     * LINE: onSuccess → reply(text)、onError → reply("⚠ " + text)
     */
    interface ResponseCallback {
        /** Agent 成功回應 */
        void onSuccess(String result);
        /** 錯誤（使用者友善訊息，Adapter 用 error 樣式渲染） */
        void onError(String userMessage);
    }

    void handleInput(String text, InputMetadata metadata, ResponseCallback callback);

    List<CommandDispatcher.CommandEntry> listAvailableCommands();
}
