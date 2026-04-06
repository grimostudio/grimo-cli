package io.github.samzhu.grimo.tui.widget;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReactionIndicatorTest {

    @Test
    void initialStateIsIdle() {
        var indicator = new ReactionIndicator(() -> {});
        assertThat(indicator.isActive()).isFalse();
        assertThat(indicator.render(80)).isNull();
    }

    @Test
    void setStateShouldActivateAndRender() {
        var indicator = new ReactionIndicator(() -> {});
        indicator.setState(ReactionIndicator.State.QUEUED, "Received...");

        assertThat(indicator.isActive()).isTrue();
        var rendered = indicator.render(80);
        assertThat(rendered).isNotNull();
        assertThat(rendered.toString()).contains("👀").contains("Received...");
    }

    @Test
    void stopShouldDeactivate() {
        var indicator = new ReactionIndicator(() -> {});
        indicator.setState(ReactionIndicator.State.THINKING, "Thinking...");
        assertThat(indicator.isActive()).isTrue();

        indicator.stop();
        assertThat(indicator.isActive()).isFalse();
        assertThat(indicator.render(80)).isNull();
    }

    @Test
    void toolStateShouldShowFireEmoji() {
        var indicator = new ReactionIndicator(() -> {});
        indicator.setState(ReactionIndicator.State.TOOL, "Using read_file...");

        var rendered = indicator.render(80);
        assertThat(rendered.toString()).contains("🔥").contains("Using read_file...");
    }

    @Test
    void codingStateShouldShowCodingEmoji() {
        var indicator = new ReactionIndicator(() -> {});
        indicator.setState(ReactionIndicator.State.CODING, "Writing code...");

        var rendered = indicator.render(80);
        assertThat(rendered.toString()).contains("👨‍💻").contains("Writing code...");
    }

    @Test
    void webStateShouldShowLightningEmoji() {
        var indicator = new ReactionIndicator(() -> {});
        indicator.setState(ReactionIndicator.State.WEB, "Searching...");

        var rendered = indicator.render(80);
        assertThat(rendered.toString()).contains("⚡").contains("Searching...");
    }

    @Test
    void respondingStateShouldShowSparkleEmoji() {
        var indicator = new ReactionIndicator(() -> {});
        indicator.setState(ReactionIndicator.State.RESPONDING, "Receiving...");

        var rendered = indicator.render(80);
        assertThat(rendered.toString()).contains("✨").contains("Receiving...");
    }

    @Test
    void errorStateShouldShowErrorEmoji() {
        var indicator = new ReactionIndicator(() -> {});
        indicator.setState(ReactionIndicator.State.ERROR, "Timeout");

        var rendered = indicator.render(80);
        assertThat(rendered.toString()).contains("😱").contains("Timeout");
    }

    @Test
    void startThinkingAnimationShouldActivate() throws InterruptedException {
        var indicator = new ReactionIndicator(() -> {});
        indicator.startThinkingAnimation();

        Thread.sleep(100); // let animation thread start
        assertThat(indicator.isActive()).isTrue();
        var rendered = indicator.render(80);
        assertThat(rendered.toString()).contains("🤔");

        indicator.stop();
    }

    @Test
    void stopShouldCancelAnimation() throws InterruptedException {
        var indicator = new ReactionIndicator(() -> {});
        indicator.startThinkingAnimation();
        Thread.sleep(100);

        indicator.stop();
        Thread.sleep(100);
        assertThat(indicator.isActive()).isFalse();
    }
}
