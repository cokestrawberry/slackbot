package com.jirabot.slack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: @ConfigurationProperties는 application.yml의 prefix 섹션을 타입 안전 빈으로 바인딩.
// STUDY: record 의 compact constructor — 바인딩된 값이 null/blank 일 때 안전 디폴트 주입.
@ConfigurationProperties(prefix = "claude")
public record ClaudeProperties(
        String cliPath,
        String model,
        int timeoutSeconds,
        String permissionMode,
        int maxTurns
) {
    public ClaudeProperties {
        if (cliPath == null || cliPath.isBlank()) {
            cliPath = "claude";
        }
        if (permissionMode == null || permissionMode.isBlank()) {
            permissionMode = "plan";
        }
        if (maxTurns <= 0) {
            maxTurns = 1;
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 60;
        }
    }
}
