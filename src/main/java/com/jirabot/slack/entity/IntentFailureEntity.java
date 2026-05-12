package com.jirabot.slack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// STUDY: Haiku 분류 실패/unknown 로그를 DB에 저장하여 프롬프트 튜닝 근거로 활용.
@Entity
@Table(name = "intent_failures")
public class IntentFailureEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT")
    private String rawInput;

    @Column(nullable = false)
    private String errorType;

    @Column(columnDefinition = "TEXT")
    private String errorDetail;

    private String slackUserId;

    private String slackChannel;

    @Column(nullable = false)
    private Instant failedAt;

    protected IntentFailureEntity() {}

    public IntentFailureEntity(String rawInput, String errorType, String errorDetail,
                               String slackUserId, String slackChannel) {
        this.rawInput = rawInput;
        this.errorType = errorType;
        this.errorDetail = errorDetail;
        this.slackUserId = slackUserId;
        this.slackChannel = slackChannel;
        this.failedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getRawInput() { return rawInput; }
    public String getErrorType() { return errorType; }
    public String getErrorDetail() { return errorDetail; }
    public String getSlackUserId() { return slackUserId; }
    public String getSlackChannel() { return slackChannel; }
    public Instant getFailedAt() { return failedAt; }
}
