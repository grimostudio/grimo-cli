/**
 * TUI module：JLine-based fullscreen terminal UI。
 *
 * 允許依賴：
 * - shared：events、sandbox 等基礎設施
 * - agent::registry：查詢可用 agent/model（status bar 計數）
 * - config：讀取 GrimoConfig（status bar agent/model/mcp 計數）
 * - skill::registry：查詢已載入 skill 數量（status bar 計數）
 * - task::scheduler：查詢已排程 task 數量（status bar 計數）
 * - project：取得專案路徑顯示（status bar projectPath）
 * - agent（根包）：存取 AgentCommands.RECOMMENDED_MODELS 常數
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared",
        "agent::registry",
        "config",
        "skill::registry",
        "task::scheduler",
        "project",
        "agent"
    }
)
package io.github.samzhu.grimo.tui;
