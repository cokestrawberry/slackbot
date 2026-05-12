package com.jirabot.slack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: @ConfigurationProperties로 application.yml의 claude.intent 섹션을 바인딩.
//        record로 선언하면 불변 + 생성자 바인딩.
@ConfigurationProperties(prefix = "claude.intent")
public record IntentProperties(
        String model,
        int timeoutSeconds,
        String promptFile
) {
    public IntentProperties {
        if (model == null || model.isBlank()) model = "claude-haiku-4-5";
        if (timeoutSeconds <= 0) timeoutSeconds = 15;
        if (promptFile == null || promptFile.isBlank()) promptFile = "prompts/haiku-classifier.md";
    }

    public String cliPath() {
        return "claude";
    }
}
