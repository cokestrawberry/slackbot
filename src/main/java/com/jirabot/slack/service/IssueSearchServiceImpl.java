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
@Service
public class IssueSearchServiceImpl implements IssueSearchService {

    private static final Logger log = LoggerFactory.getLogger(IssueSearchServiceImpl.class);

    static final int MAX_SEARCH_RESULTS = 50;
    private static final int SEARCH_MAX_DISPLAY = 10;

    private final JiraApiClient jiraApiClient;
    private final String jiraBaseUrl;

    public IssueSearchServiceImpl(JiraApiClient jiraApiClient,
                                  JiraProperties jiraProps) {
        this.jiraApiClient = jiraApiClient;
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl();
        this.jiraBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    // STUDY: @Async 메서드는 Spring 프록시를 통해 호출되어야 비동기가 적용된다.
    //        컨트롤러에서 외부 빈을 통해 호출하므로 적용된다.
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
        sb.append(String.format(":mag: \"%s\" 검색 결과 (%d건)\n", keyword, hits.size()));

        int displayCount = Math.min(hits.size(), SEARCH_MAX_DISPLAY);
        for (int i = 0; i < displayCount; i++) {
            JiraSearchHit hit = hits.get(i);
            String url = issueLink(hit.key());
            String assignee = (hit.assignee() == null || hit.assignee().isBlank())
                    ? "미배정" : hit.assignee();
            sb.append(String.format("• <%s|%s> %s (%s, 담당: %s)\n",
                    url, hit.key(), hit.summary(),
                    hit.status() == null || hit.status().isBlank() ? "-" : hit.status(),
                    assignee));
        }

        if (hits.size() > SEARCH_MAX_DISPLAY) {
            sb.append(String.format("외 %d건이 더 있습니다.", hits.size() - SEARCH_MAX_DISPLAY));
        }

        return sb.toString().stripTrailing();
    }

    private String issueLink(String key) {
        if (jiraBaseUrl.isEmpty()) return key;
        return jiraBaseUrl + "/browse/" + key;
    }
}
