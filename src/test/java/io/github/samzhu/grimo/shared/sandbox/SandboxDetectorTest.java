package io.github.samzhu.grimo.shared.sandbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxDetectorTest {

    @Test
    void localShouldAlwaysBeAvailable() {
        var detector = new SandboxDetector();
        var results = detector.detect();
        assertThat(results.localAvailable()).isTrue();
    }

    @Test
    void detectShouldReturnAllBackends() {
        var detector = new SandboxDetector();
        var results = detector.detect();
        assertThat(results.localAvailable()).isTrue();
        assertThat(results).isNotNull();
    }

    @Test
    void resolveModeShouldFallbackToLocal() {
        var detector = new SandboxDetector();
        var results = detector.detect();
        String mode = detector.resolveMode(results, "docker");
        assertThat(mode).isIn("docker", "local");
    }

    @Test
    void resolveModeShouldReturnLocalWhenRequested() {
        var detector = new SandboxDetector();
        var results = detector.detect();
        assertThat(detector.resolveMode(results, "local")).isEqualTo("local");
    }
}
