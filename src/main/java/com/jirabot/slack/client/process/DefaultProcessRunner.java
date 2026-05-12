package com.jirabot.slack.client.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// STUDY: @Component — 기본 구현. 테스트에서는 Mockito mock 으로 교체하여 실제 OS 호출 회피.
@Component
public class DefaultProcessRunner implements ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultProcessRunner.class);

    @Override
    public Result run(List<String> command, String stdin, Duration timeout) {
        // STUDY: 사용자 입력이 stdin 에 실릴 수 있으므로 command 만 로깅 (stdin 절대 금지).
        log.debug("Launching subprocess command={}", command);

        // STUDY: redirectErrorStream(false) — stdout/stderr 분리. stderr 는 진단용.
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            log.warn("Failed to spawn subprocess: {}", e.toString());
            return new Result(-1, "", e.getMessage() == null ? "" : e.getMessage(), false);
        }

        // stdin 선-공급: 대용량이 아니므로 blocking write 한 뒤 close.
        if (stdin != null && !stdin.isEmpty()) {
            try (OutputStream os = process.getOutputStream()) {
                os.write(stdin.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException e) {
                log.debug("stdin write failed (process may have exited early): {}", e.toString());
            }
        } else {
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // subprocess already closed stdin
            }
        }

        // STUDY: stdout/stderr 를 동시에 드레인하지 않으면 pipe buffer(OS에 따라 4~64KB) 가 가득 차
        // subprocess 가 write 에서 블록 → waitFor 가 영원히 대기 (classic deadlock).
        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));

        long timeoutMs = Math.max(1, timeout.toMillis());
        boolean finished;
        try {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // STUDY: interrupt flag 복원 — 상위 스레드가 추가 확인할 수 있도록. throw 하지 않는다.
            Thread.currentThread().interrupt();
            killTree(process);
            return new Result(-1, safeJoin(stdoutFuture), safeJoin(stderrFuture), true);
        }

        if (!finished) {
            killTree(process);
            return new Result(-1, safeJoin(stdoutFuture), safeJoin(stderrFuture), true);
        }

        String stdout = safeJoin(stdoutFuture);
        String stderr = safeJoin(stderrFuture);
        int exitCode = process.exitValue();
        return new Result(exitCode, stdout, stderr, false);
    }

    private static String readAll(InputStream in) {
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String safeJoin(CompletableFuture<String> f) {
        try {
            return f.get(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            return "";
        }
    }

    private static void killTree(Process process) {
        // STUDY: Node 기반 CLI 는 자식 프로세스를 남길 수 있으므로 descendants 까지 강제 종료.
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
