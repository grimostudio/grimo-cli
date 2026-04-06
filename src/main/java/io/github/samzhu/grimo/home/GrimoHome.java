package io.github.samzhu.grimo.home;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 全域目錄管理：管理 ~/.grimo 下的固定目錄結構。
 *
 * 設計說明：
 * - 取代 WorkspaceManager 中全域目錄管理的職責
 * - 不包含 conversations 目錄（已棄用）
 * - 新增 projects 目錄，供 ProjectContext 儲存 per-project 資料
 * - 無參建構子預設指向 ~/.grimo，有參建構子供測試注入
 */
public class GrimoHome {

    private final Path root;

    /**
     * 正式環境用：預設 ~/.grimo
     */
    public GrimoHome() {
        this(Path.of(System.getProperty("user.home"), ".grimo"));
    }

    /**
     * 測試用：允許注入任意根目錄
     */
    public GrimoHome(Path root) {
        this.root = root;
    }

    /**
     * 初始化全域目錄結構：建立所有必要子目錄。
     *
     * 設計說明：
     * - conversations 目錄已棄用，不再建立
     * - projects 目錄供 ProjectContext 使用，儲存 per-project 的對話與元資料
     * - config.yaml 由 GrimoConfig 建構子負責建立預設內容（不在此處理）
     */
    public void initialize() {
        createDir(tasksDir());
        createDir(skillsDir());
        createDir(agentsDir());
        createDir(logsDir());
        createDir(projectsDir());
    }

    /**
     * 檢查全域目錄是否已初始化（至少 tasks 和 skills 目錄存在）。
     */
    public boolean isInitialized() {
        return Files.isDirectory(tasksDir())
            && Files.isDirectory(skillsDir());
    }

    public Path root()        { return root; }
    public Path configFile()  { return root.resolve("config.yaml"); }
    public Path tasksDir()    { return root.resolve("tasks"); }
    public Path skillsDir()   { return root.resolve("skills"); }
    public Path agentsDir()   { return root.resolve("agents"); }
    public Path logsDir()     { return root.resolve("logs"); }
    public Path projectsDir() { return root.resolve("projects"); }

    private void createDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create directory: " + dir, e);
        }
    }
}
