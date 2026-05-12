package com.jirabot.slack.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.config.JiraWebhookProperties;
import com.jirabot.slack.config.JiraWebhookProperties.NotifyTrigger;
import com.jirabot.slack.config.NotifyProperties;
import com.jirabot.slack.config.NotifyProperties.MentionMode;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.ProcessedJiraChangelogRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// STUDY: 외부 의존을 모두 mock 으로 두고 buildMessage / shouldNotify / idempotency 경로를 검증한다.
//        SlackNotifier 호출은 인수 매칭으로 메시지 본문을 확인한다.
class JiraWebhookServiceImplTest {

    private IssueRepository issueRepository;
    private UserMappingRepository userMappingRepository;
    private ProcessedJiraChangelogRepository processedRepo;
    private SlackNotifier slackNotifier;
    private JiraStatusCategoryResolver resolver;
    private JiraWebhookServiceImpl service;

    @BeforeEach
    void setUp() {
        issueRepository = mock(IssueRepository.class);
        userMappingRepository = mock(UserMappingRepository.class);
        processedRepo = mock(ProcessedJiraChangelogRepository.class);
        slackNotifier = mock(SlackNotifier.class);
        resolver = new JiraStatusCategoryResolver();
    }

    private void rebuild(NotifyTrigger trigger, MentionMode mentionMode) {
        JiraWebhookProperties props = new JiraWebhookProperties(true, "secret", trigger);
        NotifyProperties notify = new NotifyProperties(mentionMode, 0);
        JiraProperties jiraProps = new JiraProperties(
                "https://cryptolab.atlassian.net", "u@x", "t", "ES2");
        service = new JiraWebhookServiceImpl(new ObjectMapper(), issueRepository,
                userMappingRepository, processedRepo, slackNotifier, props, notify, jiraProps, resolver);
    }

    private IssueEntity botIssue() {
        IssueEntity entity = new IssueEntity("ES2-100", "기존 요약", "작업", "해야 할 일", "해야 할 일",
                "이전 담당자", 3.0, "임종승", "본문", Instant.now(), Instant.now());
        entity.setSlackThread("C1", "1700000000.000100");
        return entity;
    }

    private String payload(String changelogId, String issueKey, String summary,
                            String fromStatus, String toStatus, String fromAssignee, String toAssignee,
                            String actorDisplay) {
        StringBuilder items = new StringBuilder();
        boolean first = true;
        if (fromStatus != null || toStatus != null) {
            items.append(String.format("{\"field\":\"status\",\"fromString\":%s,\"toString\":%s}",
                    jsonString(fromStatus), jsonString(toStatus)));
            first = false;
        }
        if (fromAssignee != null || toAssignee != null) {
            if (!first) items.append(",");
            items.append(String.format("{\"field\":\"assignee\",\"fromString\":%s,\"toString\":%s}",
                    jsonString(fromAssignee), jsonString(toAssignee)));
        }
        return String.format("""
                {"webhookEvent":"jira:issue_updated",
                 "user":{"accountId":"acc-actor","displayName":%s},
                 "issue":{"key":"%s","fields":{
                   "summary":%s,
                   "status":{"name":%s,"statusCategory":{"name":"In Progress"}},
                   "assignee":{"displayName":%s},
                   "issuetype":{"name":"작업"},
                   "updated":"2025-12-31T01:23:45.000+0900"
                 }},
                 "changelog":{"id":"%s","items":[%s]}}
                """, jsonString(actorDisplay), issueKey, jsonString(summary),
                jsonString(toStatus == null ? "진행 중" : toStatus),
                jsonString(toAssignee), changelogId, items.toString());
    }

    private String jsonString(String s) {
        return s == null ? "null" : "\"" + s.replace("\"", "\\\"") + "\"";
    }

    @Test
    void duplicateChangelogId_isIgnored() {
        rebuild(NotifyTrigger.STATUS_AND_ASSIGNEE, MentionMode.MENTION);
        when(processedRepo.existsById("clog-1")).thenReturn(true);

        service.process(payload("clog-1", "ES2-100", "요약", "해야 할 일", "진행 중", null, null, "변경자"));

        verify(issueRepository, never()).findByIssueKey(any());
        verify(slackNotifier, never()).postThreadReply(any(), any(), any());
        verify(processedRepo, never()).save(any());
    }

    @Test
    void unknownIssue_isIgnored() {
        rebuild(NotifyTrigger.STATUS_AND_ASSIGNEE, MentionMode.MENTION);
        when(processedRepo.existsById(any())).thenReturn(false);
        when(issueRepository.findByIssueKey("ES2-999")).thenReturn(Optional.empty());

        service.process(payload("clog-2", "ES2-999", "요약", "해야 할 일", "진행 중", null, null, "변경자"));

        verify(slackNotifier, never()).postThreadReply(any(), any(), any());
    }

    @Test
    void nonBotIssue_isIgnored() {
        rebuild(NotifyTrigger.STATUS_AND_ASSIGNEE, MentionMode.MENTION);
        when(processedRepo.existsById(any())).thenReturn(false);
        IssueEntity nonBot = new IssueEntity("ES2-100", "요약", "작업", "해야 할 일", "해야 할 일",
                null, null, "임종승", "본문", Instant.now(), Instant.now());
        // slackThread 미설정 = 봇 이슈 아님
        when(issueRepository.findByIssueKey("ES2-100")).thenReturn(Optional.of(nonBot));

        service.process(payload("clog-3", "ES2-100", "요약", "해야 할 일", "진행 중", null, null, "변경자"));

        verify(slackNotifier, never()).postThreadReply(any(), any(), any());
    }

    @Test
    void statusTransition_underStatusAndAssignee_notifies() {
        rebuild(NotifyTrigger.STATUS_AND_ASSIGNEE, MentionMode.MENTION);
        when(processedRepo.existsById(any())).thenReturn(false);
        when(issueRepository.findByIssueKey("ES2-100")).thenReturn(Optional.of(botIssue()));
        when(userMappingRepository.findByJiraDisplayName(anyString())).thenReturn(Optional.empty());
        when(userMappingRepository.findByJiraAccountId(anyString())).thenReturn(Optional.empty());

        service.process(payload("clog-10", "ES2-100", "요약", "해야 할 일", "진행 중", null, null, "변경자"));

        verify(slackNotifier).postThreadReply(eq("C1"), eq("1700000000.000100"),
                argThat(text -> text.contains("상태: 해야 할 일 → 진행 중")
                        && text.contains("변경자: 변경자")
                        && text.contains("ES2-100")));
        verify(processedRepo, times(1)).save(any());
    }

    @Test
    void assigneeOnly_underStatusMode_skipped() {
        rebuild(NotifyTrigger.STATUS, MentionMode.MENTION);
        when(processedRepo.existsById(any())).thenReturn(false);
        when(issueRepository.findByIssueKey("ES2-100")).thenReturn(Optional.of(botIssue()));

        service.process(payload("clog-11", "ES2-100", "요약", null, null, "이전", "신규", "변경자"));

        verify(slackNotifier, never()).postThreadReply(any(), any(), any());
        verify(processedRepo, times(1)).save(any());
    }

    @Test
    void doneTransition_underDoneOnly_notifies() {
        rebuild(NotifyTrigger.DONE_ONLY, MentionMode.MENTION);
        when(processedRepo.existsById(any())).thenReturn(false);
        when(issueRepository.findByIssueKey("ES2-100")).thenReturn(Optional.of(botIssue()));

        service.process(payload("clog-12", "ES2-100", "요약", "진행 중", "완료", null, null, "변경자"));

        verify(slackNotifier).postThreadReply(any(), any(), anyString());
    }

    @Test
    void inProgressTransition_underDoneOnly_skipped() {
        rebuild(NotifyTrigger.DONE_ONLY, MentionMode.MENTION);
        when(processedRepo.existsById(any())).thenReturn(false);
        when(issueRepository.findByIssueKey("ES2-100")).thenReturn(Optional.of(botIssue()));

        service.process(payload("clog-13", "ES2-100", "요약", "해야 할 일", "진행 중", null, null, "변경자"));

        verify(slackNotifier, never()).postThreadReply(any(), any(), any());
    }

    @Test
    void unassignedTransition_rendersMiBaeJung() {
        rebuild(NotifyTrigger.STATUS_AND_ASSIGNEE, MentionMode.MENTION);
        when(processedRepo.existsById(any())).thenReturn(false);
        when(issueRepository.findByIssueKey("ES2-100")).thenReturn(Optional.of(botIssue()));

        service.process(payload("clog-14", "ES2-100", "요약", null, null, "김영현", null, "변경자"));

        verify(slackNotifier).postThreadReply(any(), any(),
                argThat(text -> text.contains("담당자: 김영현 → 미배정")));
    }

    @Test
    void mentionMode_PLAIN_outputsPlainDisplayName() {
        rebuild(NotifyTrigger.STATUS_AND_ASSIGNEE, MentionMode.PLAIN);
        when(processedRepo.existsById(any())).thenReturn(false);
        when(issueRepository.findByIssueKey("ES2-100")).thenReturn(Optional.of(botIssue()));
        when(userMappingRepository.findByJiraAccountId("acc-actor"))
                .thenReturn(Optional.of(new UserMappingEntity("U-actor", "", "변경자", "acc-actor")));

        service.process(payload("clog-15", "ES2-100", "요약", "해야 할 일", "진행 중", null, null, "변경자"));

        verify(slackNotifier).postThreadReply(any(), any(),
                argThat(text -> !text.contains("<@U-actor>") && text.contains("변경자: 변경자")));
    }

    @Test
    void accountIdMapping_takesPrecedence_overDisplayName() {
        rebuild(NotifyTrigger.STATUS_AND_ASSIGNEE, MentionMode.MENTION);
        when(processedRepo.existsById(any())).thenReturn(false);
        when(issueRepository.findByIssueKey("ES2-100")).thenReturn(Optional.of(botIssue()));
        when(userMappingRepository.findByJiraAccountId("acc-actor"))
                .thenReturn(Optional.of(new UserMappingEntity("U-by-acc", "", "변경자", "acc-actor")));

        service.process(payload("clog-16", "ES2-100", "요약", "해야 할 일", "진행 중", null, null, "변경자"));

        verify(slackNotifier).postThreadReply(any(), any(),
                argThat(text -> text.contains("변경자: <@U-by-acc>")));
    }

    @Test
    void issueEntityIsUpdated_whenNotifying() {
        rebuild(NotifyTrigger.STATUS_AND_ASSIGNEE, MentionMode.MENTION);
        when(processedRepo.existsById(any())).thenReturn(false);
        IssueEntity issue = botIssue();
        when(issueRepository.findByIssueKey("ES2-100")).thenReturn(Optional.of(issue));

        service.process(payload("clog-17", "ES2-100", "요약 갱신", "해야 할 일", "진행 중", null, null, "변경자"));

        verify(issueRepository).save(issue);
    }
}
