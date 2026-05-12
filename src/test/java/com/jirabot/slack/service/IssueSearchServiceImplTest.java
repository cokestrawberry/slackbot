package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.dto.JiraSearchHit;
import com.jirabot.slack.config.JiraProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// STUDY: 옵션 C 도입 후 IssueSearchServiceImpl 은 Jira REST 호출 위임만 담당.
//        DB / Claude 의존이 사라졌으므로 테스트도 JiraApiClient mock 만으로 충분하다.
class IssueSearchServiceImplTest {

    private JiraApiClient jiraApiClient;
    private IssueSearchServiceImpl service;

    @BeforeEach
    void setUp() {
        jiraApiClient = mock(JiraApiClient.class);
        JiraProperties props = new JiraProperties(
                "https://cryptolab.atlassian.net/", "user@example.com", "token", "ES2");
        service = new IssueSearchServiceImpl(jiraApiClient, props);
    }

    @Test
    void searchByKeyword_emptyResult_showsNoMatchMessage() throws Exception {
        when(jiraApiClient.searchByText(eq("없는키워드"), anyInt())).thenReturn(List.of());

        String result = service.searchByKeyword("없는키워드").get();

        assertThat(result).isEqualTo(":mag: \"없는키워드\" 검색 결과가 없습니다.");
    }

    @Test
    void searchByKeyword_singleHit_formatsCorrectly() throws Exception {
        when(jiraApiClient.searchByText(eq("로그인"), anyInt())).thenReturn(List.of(
                new JiraSearchHit("ES2-100", "로그인 페이지 500 에러", "진행 중", "임종승")));

        String result = service.searchByKeyword("로그인").get();

        assertThat(result)
                .contains(":mag: \"로그인\" 검색 결과 (1건)")
                .contains("<https://cryptolab.atlassian.net/browse/ES2-100|ES2-100>")
                .contains("로그인 페이지 500 에러")
                .contains("진행 중")
                .contains("담당: 임종승");
    }

    @Test
    void searchByKeyword_unassignedHit_showsMissingAssigneeFallback() throws Exception {
        when(jiraApiClient.searchByText(eq("결제"), anyInt())).thenReturn(List.of(
                new JiraSearchHit("ES2-200", "결제 0원 표시", "해야 할 일", null)));

        String result = service.searchByKeyword("결제").get();

        assertThat(result).contains("담당: 미배정");
    }

    @Test
    void searchByKeyword_overflow_truncatesAtDisplayLimit() throws Exception {
        List<JiraSearchHit> many = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            many.add(new JiraSearchHit("ES2-" + i, "이슈 " + i, "진행 중", "담당자"));
        }
        when(jiraApiClient.searchByText(eq("이슈"), anyInt())).thenReturn(many);

        String result = service.searchByKeyword("이슈").get();

        assertThat(result)
                .contains(":mag: \"이슈\" 검색 결과 (15건)")
                .contains("외 5건이 더 있습니다.");
        // 처음 10개만 본문에 표시되어야 한다.
        assertThat(result).contains("ES2-10").doesNotContain("ES2-11");
    }

    @Test
    void searchSemantic_hitsFromNaturalQuery_returnsFormattedResult() throws Exception {
        when(jiraApiClient.searchByText(eq("결제 관련 이슈 보여줘"), anyInt())).thenReturn(List.of(
                new JiraSearchHit("ES2-300", "결제 금액 표시 오류", "진행 중", "담당자")));

        String result = service.searchSemantic("결제 관련 이슈 보여줘", "결제").get();

        assertThat(result).contains("ES2-300").contains("결제 금액 표시 오류");
        // 1차 query 로 매칭이 됐으므로 fallback 키워드 재호출은 발생하지 않아야 한다.
        verify(jiraApiClient, times(1)).searchByText(eq("결제 관련 이슈 보여줘"), anyInt());
        verifyNoMoreInteractions(jiraApiClient);
    }

    @Test
    void searchSemantic_zeroHits_retriesWithFallbackKeyword() throws Exception {
        when(jiraApiClient.searchByText(eq("애매한 자연어 질의 여러개 단어"), anyInt())).thenReturn(List.of());
        when(jiraApiClient.searchByText(eq("결제"), anyInt())).thenReturn(List.of(
                new JiraSearchHit("ES2-301", "결제 모듈 리팩터", "해야 할 일", null)));

        String result = service.searchSemantic("애매한 자연어 질의 여러개 단어", "결제").get();

        assertThat(result).contains("ES2-301").contains("결제 모듈 리팩터");
        verify(jiraApiClient).searchByText(eq("애매한 자연어 질의 여러개 단어"), anyInt());
        verify(jiraApiClient).searchByText(eq("결제"), anyInt());
    }

    @Test
    void searchSemantic_zeroHits_andFallbackEqualsQuery_doesNotRetry() throws Exception {
        when(jiraApiClient.searchByText(eq("결제"), anyInt())).thenReturn(List.of());

        String result = service.searchSemantic("결제", "결제").get();

        assertThat(result).isEqualTo(":mag: \"결제\" 검색 결과가 없습니다.");
        verify(jiraApiClient, times(1)).searchByText(eq("결제"), anyInt());
    }

    @Test
    void formatHits_empty_returnsConsistentMessage() {
        assertThat(service.formatHits("foo", Collections.emptyList()))
                .isEqualTo(":mag: \"foo\" 검색 결과가 없습니다.");
    }
}
