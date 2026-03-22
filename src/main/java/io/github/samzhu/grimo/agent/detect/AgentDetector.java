package io.github.samzhu.grimo.agent.detect;

import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Startup auto-detection for available AI agent providers.
 *
 * Scans the local environment for CLI tools (Claude CLI, Codex CLI),
 * API keys (ANTHROPIC_API_KEY, OPENAI_API_KEY), and local services (Ollama).
 * Results are used to pre-populate the AgentProviderRegistry with
 * detected providers so the user can immediately interact with them.
 */
public class AgentDetector {

    private final AgentProviderRegistry registry;

    /**
     * Represents the result of detecting a single agent provider.
     *
     * @param id        unique identifier for the provider (e.g. "anthropic", "claude-cli")
     * @param type      detection category: "cli" for command-line tools, "api" for API-based providers
     * @param detail    human-readable description of the detection outcome
     * @param available whether the provider was successfully detected and is usable
     */
    public record DetectionResult(String id, String type, String detail, boolean available) {}

    public AgentDetector(AgentProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * Runs all detection checks and returns the results.
     * Each check is independent; a failure in one does not affect others.
     */
    public List<DetectionResult> detect() {
        var results = new ArrayList<DetectionResult>();

        results.add(detectCliTool("claude-cli", "claude", "Claude Code CLI"));
        results.add(detectCliTool("codex-cli", "codex", "Codex CLI"));
        results.add(detectEnvKey("anthropic", "ANTHROPIC_API_KEY", "Anthropic API"));
        results.add(detectEnvKey("openai", "OPENAI_API_KEY", "OpenAI API"));
        results.add(detectOllama());

        return results;
    }

    /**
     * Detects whether a CLI tool is installed by running "which <command>".
     */
    private DetectionResult detectCliTool(String id, String command, String label) {
        try {
            var process = new ProcessBuilder("which", command)
                .redirectErrorStream(true)
                .start();
            int exit = process.waitFor();
            if (exit == 0) {
                String path = new String(process.getInputStream().readAllBytes()).trim();
                return new DetectionResult(id, "cli", label + " found at " + path, true);
            }
        } catch (IOException | InterruptedException _) {
            // CLI tool not found or lookup failed
        }
        return new DetectionResult(id, "cli", label + " not found", false);
    }

    /**
     * Detects whether an environment variable (API key) is set and non-blank.
     */
    private DetectionResult detectEnvKey(String id, String envVar, String label) {
        String value = System.getenv(envVar);
        boolean available = value != null && !value.isBlank();
        String detail = available ? envVar + " detected" : envVar + " not set";
        return new DetectionResult(id, "api", label + ": " + detail, available);
    }

    /**
     * Detects whether Ollama is running locally by hitting its API endpoint.
     * Uses a 2-second connection timeout to avoid blocking startup.
     */
    private DetectionResult detectOllama() {
        try {
            var process = new ProcessBuilder("curl", "-s", "--connect-timeout", "2",
                    "http://localhost:11434/api/tags")
                .redirectErrorStream(true)
                .start();
            int exit = process.waitFor();
            boolean available = exit == 0;
            return new DetectionResult("ollama", "api",
                available ? "Ollama running (localhost:11434)" : "Ollama not reachable",
                available);
        } catch (IOException | InterruptedException _) {
            return new DetectionResult("ollama", "api", "Ollama not reachable", false);
        }
    }
}
