package io.github.samzhu.grimo.shared.event;

/**
 * Agent 偵測到後發布。
 * 訂閱者：DynamicCommandRegistrar（註冊 /agentId 和 @agentId 快捷指令）。
 */
public record AgentDetectedEvent(String agentId, boolean available) {}
