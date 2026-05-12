package com.jirabot.slack.client.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultProcessRunnerTest {

    private final DefaultProcessRunner runner = new DefaultProcessRunner();

    @Test
    void echoCommand_returnsStdoutAndZeroExit() {
        ProcessRunner.Result result = runner.run(
                List.of("/bin/echo", "hello-runner"), null, Duration.ofSeconds(5));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hello-runner");
        assertThat(result.success()).isTrue();
    }

    @Test
    void nonZeroExit_isReported() {
        ProcessRunner.Result result = runner.run(
                List.of("/bin/sh", "-c", "exit 3"), null, Duration.ofSeconds(5));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.success()).isFalse();
    }

    @Test
    void longRunningCommand_timesOutAndIsKilled() {
        ProcessRunner.Result result = runner.run(
                List.of("/bin/sh", "-c", "sleep 5"), null, Duration.ofMillis(300));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.success()).isFalse();
    }
}
