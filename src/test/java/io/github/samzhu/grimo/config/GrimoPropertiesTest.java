package io.github.samzhu.grimo.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 驗證 GrimoProperties 能正確綁定 application.yaml 中 grimo.* 設定。
 * 使用 ApplicationContextRunner 避免 full Spring context + AOT 問題。
 */
class GrimoPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(GrimoProperties.class)
    static class TestConfig {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaultsShouldBindCorrectly() {
        runner.withPropertyValues(
                "grimo.defaults.claude=claude-sonnet-4-6",
                "grimo.defaults.gemini=gemini-2.5-pro",
                "grimo.defaults.codex=gpt-5.4"
        ).run(ctx -> {
            var props = ctx.getBean(GrimoProperties.class);
            assertThat(props.getDefaults()).containsEntry("claude", "claude-sonnet-4-6");
            assertThat(props.getDefaults()).containsEntry("gemini", "gemini-2.5-pro");
            assertThat(props.getDefaults()).containsEntry("codex", "gpt-5.4");
        });
    }

    @Test
    void tierModelsShouldBindFromProperties() {
        runner.withPropertyValues(
                "grimo.tier-models.pro[0].agent=claude",
                "grimo.tier-models.pro[0].model=claude-opus-4-6",
                "grimo.tier-models.std[0].agent=claude",
                "grimo.tier-models.std[0].model=claude-sonnet-4-6",
                "grimo.tier-models.lite[0].agent=gemini",
                "grimo.tier-models.lite[0].model=gemini-2.5-flash-lite"
        ).run(ctx -> {
            var props = ctx.getBean(GrimoProperties.class);
            var tierModels = props.getTierModels();
            assertThat(tierModels).containsKeys("lite", "std", "pro");
            assertThat(tierModels.get("pro")).isNotEmpty();
            assertThat(tierModels.get("pro").getFirst().agent()).isEqualTo("claude");
            assertThat(tierModels.get("pro").getFirst().model()).isEqualTo("claude-opus-4-6");
        });
    }

    @Test
    void modelsShouldBindFromProperties() {
        runner.withPropertyValues(
                "grimo.models.claude[0].id=claude-opus-4-6",
                "grimo.models.claude[0].swe-bench=81.4%",
                "grimo.models.claude[0].gpqa=88.5%",
                "grimo.models.claude[0].pricing=$5/$25",
                "grimo.models.claude[0].context=1M",
                "grimo.models.gemini[0].id=gemini-2.5-pro",
                "grimo.models.codex[0].id=gpt-5.4"
        ).run(ctx -> {
            var props = ctx.getBean(GrimoProperties.class);
            var models = props.getModels();
            assertThat(models).containsKeys("claude", "gemini", "codex");
            assertThat(models.get("claude")).isNotEmpty();
            assertThat(models.get("claude").getFirst().id()).isEqualTo("claude-opus-4-6");
            assertThat(models.get("claude").getFirst().sweBench()).isEqualTo("81.4%");
            assertThat(models.get("claude").getFirst().gpqa()).isEqualTo("88.5%");
        });
    }

    @Test
    void modelEntryShouldAllowNullOptionalFields() {
        runner.withPropertyValues(
                "grimo.models.claude[0].id=claude-haiku-4-5",
                "grimo.models.claude[0].pricing=$1/$5",
                "grimo.models.claude[0].context=200K"
        ).run(ctx -> {
            var props = ctx.getBean(GrimoProperties.class);
            var entry = props.getModels().get("claude").getFirst();
            assertThat(entry.id()).isEqualTo("claude-haiku-4-5");
            assertThat(entry.gpqa()).isNull();
            assertThat(entry.sweBench()).isNull();
        });
    }
}
