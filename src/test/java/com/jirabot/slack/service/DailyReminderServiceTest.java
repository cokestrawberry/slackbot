package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.config.ReminderProperties;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DailyReminderServiceTest {

    private UserMappingRepository userMappingRepository;
    private IssueRepository issueRepository;
    private SlackNotifier slackNotifier;
    private DailyReminderService service;

    @BeforeEach
    void setUp() {
        userMappingRepository = mock(UserMappingRepository.class);
        issueRepository = mock(IssueRepository.class);
        slackNotifier = mock(SlackNotifier.class);
    }

    private void rebuild(boolean enabled) {
        ReminderProperties reminderProps = new ReminderProperties(enabled, "0 0 9 * * *", "Asia/Seoul");
        JiraProperties jiraProps = new JiraProperties(
                "https://cryptolab.atlassian.net", "u@x", "t", "ES2");
        service = new DailyReminderService(
                userMappingRepository, issueRepository, slackNotifier, reminderProps, jiraProps);
    }

    private UserMappingEntity subscriber(String slackUserId, String jiraDisplayName) {
        UserMappingEntity entity = new UserMappingEntity(slackUserId, slackUserId, jiraDisplayName);
        entity.setReminderEnabled(true);
        return entity;
    }

    private IssueEntity issue(String key, String summary, String assignee) {
        return new IssueEntity(key, summary, "작업", "진행 중", "진행 중",
                assignee, 2.0, "reporter", "본문", Instant.now(), Instant.now());
    }

    @Test
    void enabledFalse_doesNothing() {
        rebuild(false);

        service.run();

        verify(userMappingRepository, never()).findByReminderEnabledTrue();
        verify(slackNotifier, never()).sendDirectMessage(anyString(), anyString());
    }

    @Test
    void noSubscribers_doesNothing() {
        rebuild(true);
        when(userMappingRepository.findByReminderEnabledTrue()).thenReturn(List.of());

        service.run();

        verify(slackNotifier, never()).sendDirectMessage(anyString(), anyString());
    }

    @Test
    void subscriberWithoutOpenIssues_skipsDm() {
        rebuild(true);
        when(userMappingRepository.findByReminderEnabledTrue())
                .thenReturn(List.of(subscriber("U1", "임종승")));
        when(issueRepository.findByAssigneeAndStatusCategoryNot("임종승", "완료"))
                .thenReturn(List.of());

        service.run();

        verify(slackNotifier, never()).sendDirectMessage(anyString(), anyString());
    }

    @Test
    void subscriberWithOpenIssues_sendsDm() {
        rebuild(true);
        when(userMappingRepository.findByReminderEnabledTrue())
                .thenReturn(List.of(subscriber("U1", "임종승")));
        when(issueRepository.findByAssigneeAndStatusCategoryNot("임종승", "완료"))
                .thenReturn(List.of(issue("ES2-100", "로그인 에러", "임종승")));

        service.run();

        verify(slackNotifier).sendDirectMessage(eq("U1"),
                argThat(text -> text.contains("ES2-100")
                        && text.contains("로그인 에러")
                        && text.contains("1건")));
    }

    @Test
    void oneFailedUser_doesNotBlockOthers() {
        rebuild(true);
        UserMappingEntity u1 = subscriber("U1", "임종승");
        UserMappingEntity u2 = subscriber("U2", "김영현");
        when(userMappingRepository.findByReminderEnabledTrue()).thenReturn(List.of(u1, u2));
        when(issueRepository.findByAssigneeAndStatusCategoryNot("임종승", "완료"))
                .thenReturn(List.of(issue("ES2-1", "이슈 A", "임종승")));
        when(issueRepository.findByAssigneeAndStatusCategoryNot("김영현", "완료"))
                .thenReturn(List.of(issue("ES2-2", "이슈 B", "김영현")));
        doThrow(new RuntimeException("slack down"))
                .when(slackNotifier).sendDirectMessage(eq("U1"), anyString());

        service.run();

        // 첫 사용자 실패 후에도 두 번째 사용자 발송은 시도된다.
        verify(slackNotifier, times(1)).sendDirectMessage(eq("U1"), anyString());
        verify(slackNotifier, times(1)).sendDirectMessage(eq("U2"), anyString());
    }

    @Test
    void buildMessage_includesIssueLinkAndStatus() {
        rebuild(true);
        String message = service.buildMessage(List.of(
                issue("ES2-1", "이슈 A", "임종승"),
                issue("ES2-2", "이슈 B", "임종승")));

        assertThat(message)
                .contains(":sunny:")
                .contains("2건")
                .contains("<https://cryptolab.atlassian.net/browse/ES2-1|ES2-1>")
                .contains("이슈 A")
                .contains("<https://cryptolab.atlassian.net/browse/ES2-2|ES2-2>");
    }
}
