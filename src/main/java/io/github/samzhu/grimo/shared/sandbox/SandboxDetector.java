package io.github.samzhu.grimo.shared.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 啟動時偵測可用的 Sandbox 後端（Local / Docker / E2B）。
 *
 * 設計說明：
 * - Local 永遠可用
 * - Docker 檢查 daemon 是否運行（ProcessBuilder 執行 docker info）
 * - E2B 檢查 E2B_API_KEY 環境變數
 * - 偵測結果用於驗證 config.yaml 中設定的 mode 是否可用
 */
public class SandboxDetector {

    private static final Logger log = LoggerFactory.getLogger(SandboxDetector.class);

    public record DetectionResult(
        boolean localAvailable,
        boolean dockerAvailable,
        boolean e2bAvailable
    ) {}

    /**
     * 解析實際使用的 sandbox mode。
     * 若要求的 mode 不可用，fallback 到 local 並 WARN。
     */
    public String resolveMode(DetectionResult result, String requestedMode) {
        if (requestedMode == null || requestedMode.isBlank()) return "local";
        return switch (requestedMode) {
            case "local" -> "local";
            case "docker" -> {
                if (result.dockerAvailable()) yield "docker";
                log.warn("Docker sandbox requested but not available, falling back to local");
                yield "local";
            }
            case "e2b" -> {
                if (result.e2bAvailable()) yield "e2b";
                log.warn("E2B sandbox requested but not available, falling back to local");
                yield "local";
            }
            default -> {
                log.warn("Unknown sandbox mode '{}', falling back to local", requestedMode);
                yield "local";
            }
        };
    }

    public DetectionResult detect() {
        boolean docker = checkDocker();
        boolean e2b = checkE2B();

        log.info("Sandbox backends: local ✓, docker {}, e2b {}",
                docker ? "✓" : "✗", e2b ? "✓" : "✗");

        return new DetectionResult(true, docker, e2b);
    }

    private boolean checkDocker() {
        try {
            var process = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            int exit = process.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkE2B() {
        String apiKey = System.getenv("E2B_API_KEY");
        return apiKey != null && !apiKey.isBlank();
    }
}
