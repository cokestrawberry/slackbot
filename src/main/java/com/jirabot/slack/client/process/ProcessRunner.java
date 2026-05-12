package com.jirabot.slack.client.process;

import java.time.Duration;
import java.util.List;

// STUDY: 외부 프로세스 호출을 얇은 interface 뒤에 두면 테스트에서 Mockito 로 stub 가능.
// STUDY: 직접 ProcessBuilder 호출 시 OS 바이너리 의존 → CI flaky. (lessons.md L3)
public interface ProcessRunner {

    Result run(List<String> command, String stdin, Duration timeout);

    record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean success() {
            return !timedOut && exitCode == 0;
        }
    }
}
