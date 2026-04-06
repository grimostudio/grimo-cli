/**
 * TUI module：JLine-based fullscreen terminal UI。
 *
 * 允許依賴：
 * - shared：events、sandbox 等基礎設施
 * - agent::registry：查詢可用 agent/model（status bar 計數）
 * - agent::tier：使用 TierRouter.resolveDefault() 確保 status bar 顯示與 dispatch 一致
 * - config：讀取 GrimoConfig（status bar agent/model/mcp 計數）、GrimoProperties.getDefaults()（agent 預設模型）
 * - skill::registry：查詢已載入 skill 數量（status bar 計數）
 * - task::scheduler：查詢已排程 task 數量（status bar 計數）
 * - project：取得專案路徑顯示（status bar projectPath）
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared", "shared::event", "shared::session",
        "agent::registry", "agent::tier",
        "config", "mcp",
        "skill::registry",
        "task::scheduler",
        "project"
    }
)
package io.github.samzhu.grimo.tui;
