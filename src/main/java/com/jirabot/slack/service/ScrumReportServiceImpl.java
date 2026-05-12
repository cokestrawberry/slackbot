package com.jirabot.slack.service;

import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

// STUDY: Jira API 직접 호출에서 DB 조회로 전환. 응답 속도 대폭 개선 (API 수초 → DB 수ms).
//        데이터 정확성은 앱 시작 시 + 매일 8시 자동 동기화 + @지라봇 sync 수동 동기화로 보장.
@Service
public class ScrumReportServiceImpl implements ScrumReportService {

    private static final Logger log = LoggerFactory.getLogger(ScrumReportServiceImpl.class);

    private final IssueRepository issueRepository;
    private final UserMappingRepository userMappingRepository;
    private final SlackNotifier slackNotifier;
    private final String jiraBaseUrl;

    public ScrumReportServiceImpl(IssueRepository issueRepository,
                                  UserMappingRepository userMappingRepository,
                                  SlackNotifier slackNotifier,
                                  com.jirabot.slack.config.JiraProperties jiraProps) {
        this.issueRepository = issueRepository;
        this.userMappingRepository = userMappingRepository;
        this.slackNotifier = slackNotifier;
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl();
        this.jiraBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<String> generateReport() {
        try {
            List<IssueEntity> allIssues = issueRepository.findAll();
            if (allIssues.isEmpty()) {
                return CompletableFuture.completedFuture("DB에 이슈가 없습니다. `@지라봇 sync`로 동기화해주세요.");
            }
            String report = formatReport(allIssues);
            log.info("Scrum report generated from DB, issues={}", allIssues.size());
            return CompletableFuture.completedFuture(report);
        } catch (Exception e) {
            log.error("Scrum report generation failed: {}", e.toString());
            return CompletableFuture.completedFuture("스크럼 리포트 생성에 실패했습니다: " + e.getMessage());
        }
    }

    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<String> generateMyReport(String slackUserId) {
        try {
            // STUDY: 내 이슈를 찾는 2가지 경로:
            //        1. assignee가 내 Jira 이름인 이슈 (Jira에서 배정된 것)
            //        2. reporter가 내 Slack ID인 이슈 (봇으로 생성한 것)
            String jiraName = resolveJiraName(slackUserId);

            List<IssueEntity> allIssues = issueRepository.findAll();
            List<IssueEntity> myIssues = allIssues.stream()
                    .filter(i -> isMyIssue(i, slackUserId, jiraName))
                    .toList();

            if (myIssues.isEmpty()) {
                String nameInfo = jiraName != null ? " (" + jiraName + ")" : "";
                return CompletableFuture.completedFuture(
                        "배정된 작업이 없습니다." + nameInfo
                        + "\n매핑이 안 돼있다면: `scripts/register-user-mapping.sh` 실행");
            }

            StringBuilder sb = new StringBuilder();
            String displayName = jiraName != null ? jiraName : "내";
            sb.append(String.format(":bust_in_silhouette: *%s 작업*\n\n", displayName));
            appendIssuesByStatus(sb, myIssues);

            log.info("My report generated from DB for user={} issues={}", slackUserId, myIssues.size());
            return CompletableFuture.completedFuture(sb.toString());
        } catch (Exception e) {
            log.error("My report generation failed: {}", e.toString());
            return CompletableFuture.completedFuture("내 작업 조회에 실패했습니다: " + e.getMessage());
        }
    }

    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<String> generateMemberReport(String memberName) {
        try {
            List<IssueEntity> memberIssues = issueRepository.findByAssigneeContaining(memberName);

            if (memberIssues.isEmpty()) {
                return CompletableFuture.completedFuture(
                        String.format("*%s* 님의 배정된 작업이 없습니다.", memberName));
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format(":bust_in_silhouette: *%s 님의 작업*\n\n",
                    memberIssues.get(0).getAssignee()));
            appendIssuesByStatus(sb, memberIssues);

            log.info("Member report generated from DB for name={} issues={}", memberName, memberIssues.size());
            return CompletableFuture.completedFuture(sb.toString());
        } catch (Exception e) {
            log.error("Member report generation failed: {}", e.toString());
            return CompletableFuture.completedFuture("작업 조회에 실패했습니다: " + e.getMessage());
        }
    }

    private String formatReport(List<IssueEntity> issues) {
        StringBuilder sb = new StringBuilder();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate yesterday = today.minusDays(1);
        Instant since = yesterday.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();

        sb.append(":clipboard: *스프린트 리포트*\n\n");

        // 어제~오늘 수정된 이슈 (진행한 업무)
        List<IssueEntity> recentlyUpdated = issues.stream()
                .filter(i -> i.getJiraUpdated() != null && !i.getJiraUpdated().isBefore(since))
                .filter(i -> !"해야 할 일".equals(i.getStatusCategory()))
                .toList();

        // 해야 할 일 (담당자별)
        Map<String, List<IssueEntity>> todoByAssignee = issues.stream()
                .filter(i -> "해야 할 일".equals(i.getStatusCategory()))
                .collect(Collectors.groupingBy(
                        i -> i.getAssignee() != null ? i.getAssignee() : "미배정"));

        // 담당자 전체 목록
        java.util.Set<String> allAssignees = new java.util.LinkedHashSet<>();
        recentlyUpdated.forEach(i -> allAssignees.add(
                i.getAssignee() != null ? i.getAssignee() : "미배정"));
        allAssignees.addAll(todoByAssignee.keySet());

        if (allAssignees.isEmpty()) {
            sb.append("변경된 이슈가 없습니다.\n");
        } else {
            for (String assignee : allAssignees) {
                sb.append(String.format(":bust_in_silhouette: *%s*\n", assignee));

                List<IssueEntity> worked = recentlyUpdated.stream()
                        .filter(i -> assignee.equals(
                                i.getAssignee() != null ? i.getAssignee() : "미배정"))
                        .toList();
                if (!worked.isEmpty()) {
                    Map<String, List<IssueEntity>> byStatus = worked.stream()
                            .collect(Collectors.groupingBy(IssueEntity::getStatusCategory));
                    appendStatusSection(sb, byStatus.get("완료"), "완료됨 :white_check_mark:");
                    appendStatusSection(sb, byStatus.get("진행 중"), "진행 중 :hammer:");
                }

                List<IssueEntity> todo = todoByAssignee.getOrDefault(assignee, List.of());
                if (!todo.isEmpty()) {
                    sb.append("  해야 할 일 :clipboard:\n");
                    for (IssueEntity i : todo) {
                        String sp = spText(i.getStoryPoint());
                        sb.append(String.format("    • %s %s%s\n",
                                issueLink(i.getIssueKey()), i.getSummary(), sp));
                    }
                }
                sb.append("\n");
            }
        }

        // SP 집계
        double completedSp = issues.stream()
                .filter(i -> "완료".equals(i.getStatusCategory()))
                .mapToDouble(i -> i.getStoryPoint() != null ? i.getStoryPoint() : 0)
                .sum();
        double totalSp = issues.stream()
                .mapToDouble(i -> i.getStoryPoint() != null ? i.getStoryPoint() : 0)
                .sum();
        sb.append(String.format("\n:bar_chart: *완료: %.0f SP / 전체: %.0f SP*", completedSp, totalSp));

        return sb.toString();
    }

    private void appendIssuesByStatus(StringBuilder sb, List<IssueEntity> issues) {
        Map<String, List<IssueEntity>> byStatus = issues.stream()
                .collect(Collectors.groupingBy(IssueEntity::getStatusCategory));
        appendStatusSection(sb, byStatus.get("진행 중"), "진행 중 :hammer:");
        appendStatusSection(sb, byStatus.get("해야 할 일"), "해야 할 일 :clipboard:");
        appendStatusSection(sb, byStatus.get("완료"), "완료됨 :white_check_mark:");

        double completedSp = issues.stream()
                .filter(i -> "완료".equals(i.getStatusCategory()))
                .mapToDouble(i -> i.getStoryPoint() != null ? i.getStoryPoint() : 0).sum();
        double totalSp = issues.stream()
                .mapToDouble(i -> i.getStoryPoint() != null ? i.getStoryPoint() : 0).sum();
        sb.append(String.format("\n:bar_chart: *완료: %.0f SP / 전체: %.0f SP*", completedSp, totalSp));
    }

    private void appendStatusSection(StringBuilder sb, List<IssueEntity> issues, String label) {
        if (issues == null || issues.isEmpty()) return;
        sb.append(String.format("  %s\n", label));
        for (IssueEntity i : issues) {
            String sp = spText(i.getStoryPoint());
            sb.append(String.format("    • %s %s%s\n", issueLink(i.getIssueKey()), i.getSummary(), sp));
        }
    }

    // STUDY: Slack 유저 ID → Jira displayName 변환.
    //        1순위: DB user_mappings 테이블 (수동 등록)
    //        2순위: Slack API users.info로 실명 조회 (이름이 같을 때)
    private String resolveJiraName(String slackUserId) {
        if (slackUserId == null) return null;

        // 1. DB 매핑 확인
        var mapping = userMappingRepository.findBySlackUserId(slackUserId);
        if (mapping.isPresent()) {
            return mapping.get().getJiraDisplayName();
        }

        // 2. Slack API로 실명 조회 시도
        try {
            String slackName = slackNotifier.getUserRealName(slackUserId);
            if (slackName != null && !slackName.isBlank()) {
                // 자동으로 매핑 저장 (다음번에는 DB에서 바로 조회)
                userMappingRepository.save(new UserMappingEntity(slackUserId, slackName, slackName));
                log.info("Auto-mapped Slack user {} -> Jira '{}'", slackUserId, slackName);
                return slackName;
            }
        } catch (Exception e) {
            log.warn("Failed to resolve Slack user {}: {}", slackUserId, e.toString());
        }

        return null;
    }

    private boolean isMyIssue(IssueEntity issue, String slackUserId, String jiraName) {
        // reporter가 Slack ID와 일치 (봇으로 생성한 이슈)
        if (slackUserId != null && slackUserId.equals(issue.getReporter())) {
            return true;
        }
        // assignee가 Jira displayName과 일치 (Jira에서 배정된 이슈)
        if (jiraName != null && issue.getAssignee() != null
                && issue.getAssignee().contains(jiraName)) {
            return true;
        }
        return false;
    }

    private String spText(Double sp) {
        return sp != null && sp > 0 ? String.format(" (SP %.0f)", sp) : "";
    }

    private String issueLink(String key) {
        if (jiraBaseUrl.isEmpty()) return key;
        return String.format("<%s/browse/%s|%s>", jiraBaseUrl, key, key);
    }
}
