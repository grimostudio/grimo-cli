package io.github.samzhu.grimo.shared.sandbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxDetectorTest {

    private final SandboxDetector detector = new SandboxDetector();

    // === detect() 測試 ===

    @Test
    void localShouldAlwaysBeAvailable() {
        var result = detector.detect();
        assertThat(result.localAvailable()).isTrue();
    }

    // === resolveMode() 測試（使用建構的 DetectionResult，不依賴實際環境）===

    @Test
    void resolveModeShouldReturnLocalWhenRequested() {
        var result = new SandboxDetector.DetectionResult(true, false, false);
        assertThat(detector.resolveMode(result, "local")).isEqualTo("local");
    }

    @Test
    void resolveModeShouldReturnDockerWhenAvailable() {
        var result = new SandboxDetector.DetectionResult(true, true, false);
        assertThat(detector.resolveMode(result, "docker")).isEqualTo("docker");
    }

    @Test
    void resolveModeShouldFallbackToLocalWhenDockerUnavailable() {
        var result = new SandboxDetector.DetectionResult(true, false, false);
        assertThat(detector.resolveMode(result, "docker")).isEqualTo("local");
    }

    @Test
    void resolveModeShouldReturnE2bWhenAvailable() {
        var result = new SandboxDetector.DetectionResult(true, false, true);
        assertThat(detector.resolveMode(result, "e2b")).isEqualTo("e2b");
    }

    @Test
    void resolveModeShouldFallbackToLocalWhenE2bUnavailable() {
        var result = new SandboxDetector.DetectionResult(true, false, false);
        assertThat(detector.resolveMode(result, "e2b")).isEqualTo("local");
    }

    @Test
    void resolveModeShouldFallbackToLocalForUnknownMode() {
        var result = new SandboxDetector.DetectionResult(true, true, true);
        assertThat(detector.resolveMode(result, "kubernetes")).isEqualTo("local");
    }

    @Test
    void resolveModeShouldReturnLocalForNullMode() {
        var result = new SandboxDetector.DetectionResult(true, false, false);
        assertThat(detector.resolveMode(result, null)).isEqualTo("local");
    }

    @Test
    void resolveModeShouldReturnLocalForEmptyMode() {
        var result = new SandboxDetector.DetectionResult(true, false, false);
        assertThat(detector.resolveMode(result, "")).isEqualTo("local");
    }
}
