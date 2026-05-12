package com.jirabot.slack.client.dto;

// STUDY: Jira REST /rest/api/3/search 응답의 한 row 를 Slack 응답 포맷팅에 필요한 필드만 추려 보관.
//        DB 의 IssueEntity 와 분리한 이유는 Jira API 검색이 DB 미동기화 이슈도 포함하므로 영속화 의무가 없기 때문.
public record JiraSearchHit(
        String key,
        String summary,
        String status,
        String assignee
) {}
