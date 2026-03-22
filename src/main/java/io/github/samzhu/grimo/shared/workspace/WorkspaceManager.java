package io.github.samzhu.grimo.shared.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class WorkspaceManager {

    private final Path root;

    public WorkspaceManager(Path root) {
        this.root = root;
    }

    public void initialize() {
        createDir(tasksDir());
        createDir(skillsDir());
        createDir(conversationsDir());
        createDir(logsDir());
    }

    public boolean isInitialized() {
        return Files.isDirectory(tasksDir())
            && Files.isDirectory(skillsDir());
    }

    public Path root()              { return root; }
    public Path tasksDir()          { return root.resolve("tasks"); }
    public Path skillsDir()         { return root.resolve("skills"); }
    public Path conversationsDir()  { return root.resolve("conversations"); }
    public Path logsDir()           { return root.resolve("logs"); }
    public Path configFile()        { return root.resolve("config.yaml"); }

    private void createDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create directory: " + dir, e);
        }
    }
}
