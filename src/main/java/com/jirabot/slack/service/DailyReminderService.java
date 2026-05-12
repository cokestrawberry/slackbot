package com.jirabot.slack.service;

import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.config.ReminderProperties;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// STUDY: 일일 리마인더 스케줄러.
//        - 빈은 reminder.enabled 가 false 또는 미설정(true 기본)일 때 모두 살아 있지만, 본문에서 다시 가드한다.
//          matchIfMissing=true 로 둬서 미설정 = 활성(true) 으로 본다.
//        - 실제 대상은 opt-in 한 사용자(UserMappingEntity.reminderEnabled=true) 만.
//        - 사용자가 0건이면 발송 자체를 생략하여 DM 소음을 최소화.
@Component
@ConditionalOnProperty(prefix = "reminder", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DailyReminderService {

    private static final Logger log = LoggerFactory.getLogger(DailyReminderService.class);

    private final UserMappingRepository userMappingRepository;
    private final IssueRepository issueRepository;
    private final SlackNotifier slackNotifier;
    private final ReminderProperties reminderProps;
    private final String jiraBaseUrl;

    public DailyReminderService(UserMappingRepository userMappingRepository,
                                IssueRepository issueRepository,
                                SlackNotifier slackNotifier,
                                ReminderProperties reminderProps,
                                JiraProperties jiraProps) {
        this.userMappingRepository = userMappingRepository;
        this.issueRepository = issueRepository;
        this.slackNotifier = slackNotifier;
        this.reminderProps = reminderProps;
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl();
        this.jiraBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Scheduled(cron = "${reminder.cron:0 0 9 * * MON-FRI}", zone = "${reminder.zone:Asia/Seoul}")
    public void run() {
        // STUDY: yml hot-reload 케이스 대비 — 부팅 시 enabled=true 였더라도 운영 중 false 로 바꾸면 즉시 정지.
        if (!reminderProps.enabled()) {
            log.info("Daily reminder skipped: reminder.enabled=false");
            return;
        }
        List<UserMappingEntity> subscribers = userMappingRepository.findByReminderEnabledTrue();
        log.info("Daily reminder tick: subscribers={}", subscribers.size());
        for (UserMappingEntity user : subscribers) {
            sendForUser(user);
        }
    }

    private void sendForUser(UserMappingEntity user) {
        try {
            List<IssueEntity> openIssues = issueRepository
                    .findByAssigneeAndStatusCategoryNot(user.getJiraDisplayName(), "완료");
            if (openIssues.isEmpty()) {
                log.debug("No open issues for slackUserId={}", user.getSlackUserId());
                return;
            }
            String message = buildMessage(openIssues);
            slackNotifier.sendDirectMessage(user.getSlackUserId(), message);
        } catch (Exception e) {
            // 한 사용자 실패가 전체 발송을 막지 않도록 warn 만.
            log.warn("Reminder DM failed for slackUserId={}: {}", user.getSlackUserId(), e.toString());
        }
    }

    String buildMessage(List<IssueEntity> openIssues) {
        StringBuilder sb = new StringBuilder();
        sb.append(":sunny: 좋은 아침입니다. 미해결 이슈 ").append(openIssues.size()).append("건이 있습니다.\n");
        for (IssueEntity issue : openIssues) {
            String url = issueLink(issue.getIssueKey());
            String status = (issue.getStatusCategory() == null || issue.getStatusCategory().isBlank())
                    ? "-" : issue.getStatusCategory();
            sb.append("• <").append(url).append("|").append(issue.getIssueKey()).append("> ")
                    .append(issue.getSummary()).append(" (").append(status).append(")\n");
        }
        return sb.toString().stripTrailing();
    }

    private String issueLink(String key) {
        if (jiraBaseUrl.isEmpty()) return key;
        return jiraBaseUrl + "/browse/" + key;
    }
}
