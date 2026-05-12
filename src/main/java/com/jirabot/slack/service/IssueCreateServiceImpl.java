package com.jirabot.slack.service;

import com.jirabot.slack.client.ClaudeApiClient;
import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.client.dto.IntentResult;
import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.client.dto.JiraCreateResponse;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.dto.IssueCreateCommand;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.repository.IssueRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

// STUDY: @Async는 반드시 proxy를 거쳐 호출될 때만 동작 (self-invocation 금지).
// 컨트롤러에서 이 서비스를 주입받아 호출하므로 OK.
@Service
public class IssueCreateServiceImpl implements IssueCreateService {

    private static final Logger log = LoggerFactory.getLogger(IssueCreateServiceImpl.class);

    private final ClaudeApiClient claude;
    private final JiraApiClient jira;
    private final JiraProperties jiraProps;
    private final SlackNotifier slackNotifier;
    private final DuplicateDetectionService duplicateDetection;
    private final IssueRepository issueRepository;
    private final com.jirabot.slack.repository.UserMappingRepository userMappingRepository;

    public IssueCreateServiceImpl(ClaudeApiClient claude, JiraApiClient jira,
                                  JiraProperties jiraProps, SlackNotifier slackNotifier,
                                  DuplicateDetectionService duplicateDetection,
                                  IssueRepository issueRepository,
                                  com.jirabot.slack.repository.UserMappingRepository userMappingRepository) {
        this.claude = claude;
        this.jira = jira;
        this.jiraProps = jiraProps;
        this.slackNotifier = slackNotifier;
        this.duplicateDetection = duplicateDetection;
        this.issueRepository = issueRepository;
        this.userMappingRepository = userMappingRepository;
    }

    @Override
    public IssueClassification classifyOnly(String rawText, IntentResult intentHint) {
        return claude.classify(rawText, intentHint);
    }

    // STUDY: @Async(qualifier)로 명시 executor 지정 → security-config-engineer의 "slackTaskExecutor" pool 사용.
    // Controller는 fire-and-forget이므로 포화 시 AbortPolicy로 RejectedExecutionException 발생 → AsyncUncaughtExceptionHandler에서 warn 처리.
    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<IssueCreateResult> createFromSlackText(IssueCreateCommand command) {
        return createFromSlackText(command, null);
    }

    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<IssueCreateResult> createFromSlackText(IssueCreateCommand command, IntentResult intentHint) {
        try {
            log.info("Classify request user={} textLen={} intentHint={}", command.slackUserId(),
                    command.rawText() == null ? 0 : command.rawText().length(),
                    intentHint != null ? intentHint.intent() : "none");
            IssueClassification classification = claude.classify(command.rawText(), intentHint);

            // 중복 감지: Jira 생성 전에 DB에서 유사 이슈 검색
            List<IssueEntity> similar = duplicateDetection.findSimilar(classification.title());
            if (!similar.isEmpty()) {
                log.info("Found {} similar issues for '{}'", similar.size(), classification.title());
            }

            // STUDY: Slack 유저 ID → 실명 변환. Jira description에 실명이 표시되도록.
            String reporterName = resolveReporterName(command.slackUserId());
            JiraCreateResponse created = jira.createIssue(classification, reporterName);
            String url = buildIssueUrl(created.key());
            log.info("Issue created key={} url={} type={} sp={}", created.key(), url,
                    classification.type(), classification.storyPoint());
            saveToDb(created.key(), classification, command.slackUserId(), command);
            notifySlack(command, created.key(), url, classification, similar);
            return CompletableFuture.completedFuture(IssueCreateResult.ok(created.key(), url));
        } catch (Exception e) {
            log.error("Issue creation failed: {}", e.toString());
            return CompletableFuture.completedFuture(IssueCreateResult.failure(e.getMessage()));
        }
    }

    private void notifySlack(IssueCreateCommand command, String key, String url,
                             IssueClassification classification, List<IssueEntity> similar) {
        if (command.channel() == null || command.eventTs() == null) {
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append(String.format(
                ":white_check_mark: Jira 이슈가 등록되었습니다!\n*[%s] %s*\n분류: %s | Story Point: %d\n%s",
                key, classification.title(), classification.type(),
                classification.storyPoint(), url));

        if (!similar.isEmpty()) {
            message.append("\n\n:warning: *유사한 이슈가 존재합니다:*\n");
            for (IssueEntity s : similar) {
                String issueUrl = buildIssueUrl(s.getIssueKey());
                message.append(String.format("  • <%s|%s> %s (%s)\n",
                        issueUrl, s.getIssueKey(), s.getSummary(), s.getStatus()));
            }
            message.append("중복이라면 새 이슈를 닫아주세요.");
        }

        slackNotifier.postThreadReply(command.channel(), command.eventTs(), message.toString());
    }

    private String resolveReporterName(String slackUserId) {
        if (slackUserId == null) return "unknown";
        try {
            var mapping = userMappingRepository.findBySlackUserId(slackUserId);
            if (mapping.isPresent()) {
                return mapping.get().getJiraDisplayName();
            }
            // 매핑 없으면 Slack API로 실명 조회 + 자동 매핑 저장
            String realName = slackNotifier.getUserRealName(slackUserId);
            if (realName != null && !realName.isBlank()) {
                userMappingRepository.save(
                        new com.jirabot.slack.entity.UserMappingEntity(slackUserId, realName, realName));
                log.info("Auto-mapped reporter: {} → {}", slackUserId, realName);
                return realName;
            }
        } catch (Exception e) {
            log.warn("Failed to resolve reporter name for {}: {}", slackUserId, e.toString());
        }
        return slackUserId;
    }

    private void saveToDb(String issueKey, IssueClassification c, String reporter,
                          IssueCreateCommand command) {
        try {
            String issueType = c.type() == IssueClassification.IssueType.BUG ? "버그" : "작업";
            IssueEntity entity = new IssueEntity(
                    issueKey, c.title(), issueType, "해야 할 일", "해야 할 일",
                    null, (double) c.storyPoint(), reporter, c.summary(),
                    Instant.now(), Instant.now());
            // 스레드에서 "@지라봇 완료" 시 이슈를 찾을 수 있도록 Slack 스레드 정보 저장
            if (command.channel() != null && command.eventTs() != null) {
                entity.setSlackThread(command.channel(), command.eventTs());
            }
            issueRepository.save(entity);
            log.debug("Issue saved to DB key={}", issueKey);
        } catch (Exception e) {
            log.warn("Failed to save issue to DB (non-fatal): {}", e.toString());
        }
    }

    private String buildIssueUrl(String key) {
        String base = jiraProps.baseUrl();
        if (base == null || base.isBlank()) {
            return key;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/browse/" + key;
    }
}
