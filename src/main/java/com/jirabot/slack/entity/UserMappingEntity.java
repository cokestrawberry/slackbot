package com.jirabot.slack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// STUDY: Slack 유저 ID와 Jira displayName 매핑 테이블.
//        Slack API로 자동 조회한 이름과 Jira 이름이 다를 경우 수동 등록으로 오버라이드.
@Entity
@Table(name = "user_mappings")
public class UserMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String slackUserId;

    private String slackDisplayName;

    @Column(nullable = false)
    private String jiraDisplayName;

    protected UserMappingEntity() {}

    public UserMappingEntity(String slackUserId, String slackDisplayName, String jiraDisplayName) {
        this.slackUserId = slackUserId;
        this.slackDisplayName = slackDisplayName;
        this.jiraDisplayName = jiraDisplayName;
    }

    public Long getId() { return id; }
    public String getSlackUserId() { return slackUserId; }
    public String getSlackDisplayName() { return slackDisplayName; }
    public String getJiraDisplayName() { return jiraDisplayName; }

    public void setJiraDisplayName(String jiraDisplayName) {
        this.jiraDisplayName = jiraDisplayName;
    }

    public void setSlackDisplayName(String slackDisplayName) {
        this.slackDisplayName = slackDisplayName;
    }
}
