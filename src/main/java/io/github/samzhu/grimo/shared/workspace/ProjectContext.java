package io.github.samzhu.grimo.shared.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 專案上下文：由啟動時的 CWD 決定。
 *
 * 設計說明：
 * - CWD 代表開發者目前操作的專案
 * - 專案資料（session、dispatch）存放在 ~/.grimo/projects/{encoded-cwd}/
 * - 對齊 Claude Code 的 projects/ 結構
 * - encoded-cwd 用 replaceAll("[^a-zA-Z0-9]", "-")
 *
 * @see GrimoHome 全域 app 資料管理
 */
public class ProjectContext {
    private final Path projectPath;
    private final Path dataDir;

    /**
     * 正式環境用：CWD 取自 System property "user.dir"
     */
    public ProjectContext(GrimoHome grimoHome) {
        this(grimoHome, Path.of(System.getProperty("user.dir")));
    }

    /**
     * 測試用：允許注入任意 CWD
     */
    public ProjectContext(GrimoHome grimoHome, Path cwd) {
        this.projectPath = cwd;
        this.dataDir = grimoHome.projectsDir().resolve(encodePath(cwd));
    }

    /**
     * 建立專案資料目錄。應在 GrimoHome.initialize() 之後呼叫。
     */
    public void initialize() {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create project data dir: " + dataDir, e);
        }
    }

    /** 原始 CWD 路徑 */
    public Path path() { return projectPath; }

    /**
     * 顯示用路徑：若 CWD 在 user.home 下，以 ~ 取代 home 前綴。
     */
    public String displayPath() {
        return projectPath.toString()
                .replace(System.getProperty("user.home"), "~");
    }

    /** 專案資料目錄（~/.grimo/projects/{encoded-cwd}/） */
    public Path dataDir() { return dataDir; }

    /**
     * 將路徑編碼為安全的目錄名稱：非英數字元一律替換為 "-"。
     * 對齊 Claude Code 的 projects/ 編碼慣例。
     */
    private String encodePath(Path p) {
        return p.toString().replaceAll("[^a-zA-Z0-9]", "-");
    }
}
