package com.jirabot.slack.entity;

import com.jirabot.slack.util.SensitiveDataMasker;
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
        // STUDY: rawInput은 Slack 사용자가 붙여넣은 임의 텍스트이므로 토큰/이메일 leak 우려가 있다.
        //        영속화 전에 잘 알려진 비밀정보 패턴은 마스킹한다.
        this.rawInput = SensitiveDataMasker.mask(rawInput);
        this.errorType = errorType;
        this.errorDetail = SensitiveDataMasker.mask(errorDetail);
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
