package com.jirabot.slack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: 일일 리마인더 설정. enabled 는 전역 비상 차단용으로만 사용 (기본 true).
//        개별 사용자 opt-in 은 Slack 명령으로 처리하며 UserMappingEntity.reminderEnabled 에 저장된다.
//        cron / zone 은 평일 09:00 KST 기본.
@ConfigurationProperties(prefix = "reminder")
public record ReminderProperties(
        boolean enabled,
        String cron,
        String zone
) {}
