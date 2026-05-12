package com.jirabot.slack.service;

import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.dto.JiraSearchHit;
import com.jirabot.slack.config.JiraProperties;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

// STUDY: 검색을 Jira REST API 에 직접 위임한다 (DB 사전 동기화 불필요).
//        searchByKeyword / searchSemantic 모두 동일한 JiraApiClient.searchByText 를 호출하므로,
//        Jira UI 의 Advanced Search 와 동일한 결과 범위를 갖는다.
//        의미 검색(searchSemantic) 의 자연어 query 는 Jira 의 text ~ 풀텍스트 매칭에 그대로 위임.
//        향후 자연어 → JQL 변환 단계가 필요해지면 이 클래스 안에서 Sonnet 호출을 추가하면 된다.
//
//        결과 노출 정책: 항상 최상위 5개만 가져와 번호 목록으로 표시한다. 총 개수는 노출하지 않는다.
//        결과가 5개일 때만 더 있을 가능성을 알리는 "(이하 생략)" 라인을 덧붙인다.
@Service
public class IssueSearchServiceImpl implements IssueSearchService {

    private static final Logger log = LoggerFactory.getLogger(IssueSearchServiceImpl.class);

    static final int MAX_SEARCH_RESULTS = 5;

    private final JiraApiClient jiraApiClient;
    private final String jiraBaseUrl;

    public IssueSearchServiceImpl(JiraApiClient jiraApiClient,
                                  JiraProperties jiraProps) {
        this.jiraApiClient = jiraApiClient;
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl();
        this.jiraBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<String> searchByKeyword(String keyword) {
        log.info("Keyword search requested: keyword='{}'", keyword);
        List<JiraSearchHit> hits = jiraApiClient.searchByText(keyword, MAX_SEARCH_RESULTS);
        return CompletableFuture.completedFuture(formatHits(keyword, hits));
    }

    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<String> searchSemantic(String userQuery, String fallbackKeyword) {
        log.info("Semantic search requested: query='{}' fallback='{}'", userQuery, fallbackKeyword);
        // STUDY: 1차로 사용자 자연어 그대로 Jira text ~ 매칭. Jira 의 풀텍스트 인덱스가 한국어 토크나이징도 처리.
        List<JiraSearchHit> hits = jiraApiClient.searchByText(userQuery, MAX_SEARCH_RESULTS);
        if (hits.isEmpty() && fallbackKeyword != null && !fallbackKeyword.isBlank()
                && !fallbackKeyword.equals(userQuery)) {
            // STUDY: 자연어 query 가 너무 길거나 노이즈가 많아 0건일 때, Haiku 가 추출해 둔 fallbackKeyword 로 한 번 더 시도.
            log.info("Empty result for query, retrying with fallback keyword: '{}'", fallbackKeyword);
            hits = jiraApiClient.searchByText(fallbackKeyword, MAX_SEARCH_RESULTS);
            return CompletableFuture.completedFuture(formatHits(fallbackKeyword, hits));
        }
        return CompletableFuture.completedFuture(formatHits(userQuery, hits));
    }

    // STUDY: 패키지-프라이빗으로 선언하여 테스트에서 직접 호출 가능.
    String formatHits(String keyword, List<JiraSearchHit> hits) {
        if (hits.isEmpty()) {
            return String.format(":mag: \"%s\" 검색 결과가 없습니다.", keyword);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(":mag: 검색결과 최상위 ").append(MAX_SEARCH_RESULTS).append("개입니다:\n");

        int displayCount = Math.min(hits.size(), MAX_SEARCH_RESULTS);
        for (int i = 0; i < displayCount; i++) {
            JiraSearchHit hit = hits.get(i);
            String url = issueLink(hit.key());
            String assignee = (hit.assignee() == null || hit.assignee().isBlank())
                    ? "미배정" : hit.assignee();
            sb.append(i + 1).append(". <").append(url).append("|").append(hit.key()).append("> ")
                    .append(hit.summary()).append(" (")
                    .append(hit.status() == null || hit.status().isBlank() ? "-" : hit.status())
                    .append(", 담당: ").append(assignee).append(")\n");
        }

        // STUDY: 5개를 가득 채워 받은 경우에만 더 있을 가능성이 있음을 표기.
        //        그 이하라면 결과가 완전하다는 의미이므로 생략 라인을 붙이지 않는다.
        if (hits.size() >= MAX_SEARCH_RESULTS) {
            sb.append("(이하 생략)");
        }

        return sb.toString().stripTrailing();
    }

    private String issueLink(String key) {
        if (jiraBaseUrl.isEmpty()) return key;
        return jiraBaseUrl + "/browse/" + key;
    }
}
