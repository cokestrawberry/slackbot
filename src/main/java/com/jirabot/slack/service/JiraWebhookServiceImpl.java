package com.jirabot.slack.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.config.JiraWebhookProperties;
import com.jirabot.slack.config.JiraWebhookProperties.NotifyTrigger;
import com.jirabot.slack.config.NotifyProperties;
import com.jirabot.slack.config.NotifyProperties.MentionMode;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.ProcessedJiraChangelog;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.ProcessedJiraChangelogRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// STUDY: Jira → 봇 webhook 처리의 핵심.
//        1) idempotency 체크 (processed_jira_changelog) → 2) 봇 이슈 여부 확인 → 3) trigger 평가
//        → 4) 메시지 빌드 + Slack 전송 → 5) DB 일관성 (IssueEntity.updateFrom) → 6) 처리 기록 저장.
//        실패해도 항상 200 반환을 보장하기 위해 호출 측에서 예외를 삼키지 않고 그대로 위로 던진다 (컨트롤러가 200 회신).
@Service
public class JiraWebhookServiceImpl implements JiraWebhookService {

    private static final Logger log = LoggerFactory.getLogger(JiraWebhookServiceImpl.class);

    private final ObjectMapper objectMapper;
    private final IssueRepository issueRepository;
    private final UserMappingRepository userMappingRepository;
    private final ProcessedJiraChangelogRepository processedRepo;
    private final SlackNotifier slackNotifier;
    private final JiraWebhookProperties webhookProps;
    private final NotifyProperties notifyProps;
    private final JiraProperties jiraProps;
    private final JiraStatusCategoryResolver resolver;

    public JiraWebhookServiceImpl(ObjectMapper objectMapper,
                                  IssueRepository issueRepository,
                                  UserMappingRepository userMappingRepository,
                                  ProcessedJiraChangelogRepository processedRepo,
                                  SlackNotifier slackNotifier,
                                  JiraWebhookProperties webhookProps,
                                  NotifyProperties notifyProps,
                                  JiraProperties jiraProps,
                                  JiraStatusCategoryResolver resolver) {
        this.objectMapper = objectMapper;
        this.issueRepository = issueRepository;
        this.userMappingRepository = userMappingRepository;
        this.processedRepo = processedRepo;
        this.slackNotifier = slackNotifier;
        this.webhookProps = webhookProps;
        this.notifyProps = notifyProps;
        this.jiraProps = jiraProps;
        this.resolver = resolver;
    }

    @Override
    public void process(String jsonBody) {
        try {
            JsonNode root = objectMapper.readTree(jsonBody);

            String changelogId = root.path("changelog").path("id").asText(null);
            if (changelogId == null || changelogId.isBlank()) {
                log.debug("Webhook ignored: changelog.id missing");
                return;
            }
            if (processedRepo.existsById(changelogId)) {
                log.info("Webhook duplicate ignored changelogId={}", changelogId);
                return;
            }

            String issueKey = root.path("issue").path("key").asText(null);
            if (issueKey == null || issueKey.isBlank()) {
                log.debug("Webhook ignored: issue.key missing");
                return;
            }

            Optional<IssueEntity> found = issueRepository.findByIssueKey(issueKey);
            if (found.isEmpty()) {
                log.debug("Webhook ignored: issue {} not tracked locally", issueKey);
                return;
            }
            IssueEntity issue = found.get();
            if (issue.getSlackChannel() == null || issue.getSlackThreadTs() == null) {
                log.debug("Webhook ignored: issue {} has no Slack thread", issueKey);
                return;
            }

            List<JiraChangelog> items = parseChangelogItems(root.path("changelog").path("items"));
            if (items.isEmpty()) {
                processedRepo.save(new ProcessedJiraChangelog(changelogId));
                return;
            }

            // STUDY: 트리거 평가 후 메시지를 만들고 알림 발송. updateFrom 으로 DB 일관성도 함께 갱신.
            boolean shouldNotify = shouldNotify(items);
            if (shouldNotify) {
                String message = buildMessage(root, issue, items);
                slackNotifier.postThreadReply(issue.getSlackChannel(), issue.getSlackThreadTs(), message);
                applyIssueUpdate(root, issue);
                log.info("Webhook notified key={} changelogId={}", issueKey, changelogId);
            } else {
                log.debug("Webhook below threshold notify-on={} items={}", webhookProps.notifyOn(), items.size());
            }

            processedRepo.save(new ProcessedJiraChangelog(changelogId));
        } catch (Exception e) {
            // STUDY: 예외 시에도 컨트롤러가 200 으로 응답하지만, 디버깅 위해 warn 로그 남긴다.
            //        idempotency 기록을 못 남기므로 다음 재전송 시 다시 시도된다 (정상 동작 회복).
            log.warn("Jira webhook processing failed: {}", e.toString(), e);
        }
    }

    private List<JiraChangelog> parseChangelogItems(JsonNode itemsNode) {
        List<JiraChangelog> items = new ArrayList<>();
        if (!itemsNode.isArray()) return items;
        for (JsonNode item : itemsNode) {
            items.add(new JiraChangelog(
                    item.path("field").asText(""),
                    item.path("fromString").isNull() ? null : item.path("fromString").asText(null),
                    item.path("toString").isNull() ? null : item.path("toString").asText(null)));
        }
        return items;
    }

    boolean shouldNotify(List<JiraChangelog> items) {
        NotifyTrigger mode = webhookProps.notifyOn() == null
                ? NotifyTrigger.STATUS_AND_ASSIGNEE : webhookProps.notifyOn();
        for (JiraChangelog item : items) {
            String field = item.field();
            switch (mode) {
                case STATUS -> {
                    if ("status".equals(field)) return true;
                }
                case STATUS_CATEGORY -> {
                    if ("status".equals(field)
                            && !resolver.categoryOf(item.fromValue()).equals(resolver.categoryOf(item.toValue()))) {
                        return true;
                    }
                }
                case DONE_ONLY -> {
                    if ("status".equals(field)
                            && resolver.isDone(item.toValue()) && !resolver.isDone(item.fromValue())) {
                        return true;
                    }
                }
                case STATUS_AND_ASSIGNEE -> {
                    if ("status".equals(field) || "assignee".equals(field)) return true;
                }
            }
        }
        return false;
    }

    private String buildMessage(JsonNode root, IssueEntity issue, List<JiraChangelog> items) {
        String issueUrl = issueLink(issue.getIssueKey());
        String summary = root.path("issue").path("fields").path("summary").asText(issue.getSummary());

        StringBuilder sb = new StringBuilder();
        sb.append(":arrows_counterclockwise: <").append(issueUrl).append("|")
                .append(issue.getIssueKey()).append("> ").append(summary).append("\n");

        JiraChangelog statusItem = items.stream().filter(i -> "status".equals(i.field())).findFirst().orElse(null);
        JiraChangelog assigneeItem = items.stream().filter(i -> "assignee".equals(i.field())).findFirst().orElse(null);

        if (statusItem != null) {
            sb.append("상태: ").append(orDefault(statusItem.fromValue(), "-"))
                    .append(" → ").append(orDefault(statusItem.toValue(), "-")).append("\n");
        }
        if (assigneeItem != null) {
            sb.append("담당자: ").append(orDefault(assigneeItem.fromValue(), "미배정"))
                    .append(" → ").append(orDefault(assigneeItem.toValue(), "미배정")).append("\n");
        }

        // STUDY: reporter 멘션. IssueEntity.reporter 는 Jira displayName 이 저장됨.
        String reporterDisplay = issue.getReporter();
        String reporterMention = resolveMention(null, reporterDisplay);
        sb.append("reporter: ").append(reporterMention).append("\n");

        // STUDY: actor 멘션. webhook payload 최상위 user 노드.
        JsonNode userNode = root.path("user");
        String actorAccountId = userNode.path("accountId").isMissingNode() ? null : userNode.path("accountId").asText(null);
        String actorDisplay = userNode.path("displayName").isMissingNode() ? null : userNode.path("displayName").asText(null);
        if (userNode.isMissingNode() || userNode.isNull() || (actorAccountId == null && actorDisplay == null)) {
            sb.append("변경자: 자동화/시스템\n");
        } else {
            sb.append("변경자: ").append(resolveMention(actorAccountId, actorDisplay)).append("\n");
        }

        // STUDY: 신규 담당자 라인. 단, actor 또는 reporter 와 같으면 중복 생략.
        if (assigneeItem != null && assigneeItem.toValue() != null && !assigneeItem.toValue().isBlank()) {
            String newAssignee = assigneeItem.toValue();
            boolean sameAsActor = newAssignee.equals(actorDisplay);
            boolean sameAsReporter = newAssignee.equals(reporterDisplay);
            if (!sameAsActor && !sameAsReporter) {
                sb.append("신규 담당자: ").append(resolveMention(null, newAssignee)).append("\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    private String resolveMention(String jiraAccountId, String jiraDisplayName) {
        // STUDY: 매핑 조회 우선순위 — accountId → displayName → 평문.
        Optional<UserMappingEntity> mapping = Optional.empty();
        if (jiraAccountId != null && !jiraAccountId.isBlank()) {
            mapping = userMappingRepository.findByJiraAccountId(jiraAccountId);
        }
        if (mapping.isEmpty() && jiraDisplayName != null && !jiraDisplayName.isBlank()) {
            mapping = userMappingRepository.findByJiraDisplayName(jiraDisplayName);
        }
        String fallback = (jiraDisplayName == null || jiraDisplayName.isBlank()) ? "(미상)" : jiraDisplayName;
        if (mapping.isEmpty()) {
            return fallback;
        }
        MentionMode mode = notifyProps.mention() == null ? MentionMode.MENTION : notifyProps.mention();
        if (mode == MentionMode.PLAIN) {
            return fallback;
        }
        return "<@" + mapping.get().getSlackUserId() + ">";
    }

    private void applyIssueUpdate(JsonNode root, IssueEntity issue) {
        JsonNode fields = root.path("issue").path("fields");
        String summary = fields.path("summary").asText(issue.getSummary());
        String issueType = fields.path("issuetype").path("name").asText(issue.getIssueType());
        String status = fields.path("status").path("name").asText(issue.getStatus());
        String statusCategoryRaw = fields.path("status").path("statusCategory").path("name").asText(issue.getStatusCategory());
        String statusCategory = resolveStatusCategoryKorean(statusCategoryRaw);
        JsonNode assigneeNode = fields.path("assignee");
        String assignee = (assigneeNode.isMissingNode() || assigneeNode.isNull())
                ? null : assigneeNode.path("displayName").asText(issue.getAssignee());
        JsonNode spNode = fields.path("customfield_10016");
        Double storyPoint = (spNode.isMissingNode() || spNode.isNull()) ? issue.getStoryPoint() : spNode.asDouble();
        Instant jiraUpdated = parseInstantOrFallback(fields.path("updated").asText(null));

        issue.updateFrom(summary, issueType, status, statusCategory, assignee, storyPoint, jiraUpdated);
        issueRepository.save(issue);
    }

    private String resolveStatusCategoryKorean(String rawCategoryName) {
        // STUDY: 한국어 Jira 사이트는 "완료"/"진행 중"/"해야 할 일" 등으로 반환되고, 영어는 "Done"/"In Progress"/"To Do" 등.
        //        IssueEntity 가 한국어 표기로 저장돼 있어 영어가 오면 매핑한다.
        if (rawCategoryName == null) return null;
        String lower = rawCategoryName.toLowerCase().strip();
        return switch (lower) {
            case "done", "complete" -> "완료";
            case "in progress", "indeterminate" -> "진행 중";
            case "to do", "new" -> "해야 할 일";
            default -> rawCategoryName;
        };
    }

    private Instant parseInstantOrFallback(String iso) {
        if (iso == null || iso.isBlank()) return Instant.now();
        try {
            // STUDY: Jira 의 timestamp 는 "2025-12-31T01:23:45.000+0900" 형태. Instant.parse 가 거부할 수 있어 try/catch.
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return Instant.now();
        }
    }

    private String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private String issueLink(String key) {
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl();
        if (base.isBlank()) return key;
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/browse/" + key;
    }
}
