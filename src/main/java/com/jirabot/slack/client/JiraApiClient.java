package com.jirabot.slack.client;

import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.client.dto.JiraCreateResponse;
import com.jirabot.slack.client.dto.JiraSearchHit;
import com.jirabot.slack.client.dto.SprintInfo;
import com.jirabot.slack.client.dto.SprintIssue;
import java.util.List;
import java.util.Optional;

public interface JiraApiClient {

    JiraCreateResponse createIssue(IssueClassification classification, String reporterName,
                                   String jiraAccountId);

    /**
     * Jira displayName으로 유저를 검색하여 accountId를 반환한다.
     */
    String findAccountId(String displayName);

    Optional<SprintInfo> getActiveSprint();

    List<SprintIssue> getSprintIssues(int sprintId);

    /**
     * Jira 이슈의 상태를 전환한다.
     *
     * @param issueKey 이슈 키 (예: SLAC-7)
     * @param targetStatusName 목표 상태명 (예: "완료")
     * @return 성공 여부
     */
    boolean transitionIssue(String issueKey, String targetStatusName);

    /**
     * Create a sub-task under a parent issue.
     */
    String createSubTask(String parentKey, String summary, int storyPoint);

    /**
     * Add a comment to an existing issue.
     */
    void addComment(String issueKey, String commentText);

    /**
     * Append text to an existing issue's description.
     */
    void appendDescription(String issueKey, String additionalText);

    /**
     * Jira REST /rest/api/3/search 를 통한 풀텍스트 검색.
     * JQL `text ~ "<query>"` 형태로 summary / description / comment 를 모두 본다.
     * DB 동기화 여부와 무관하게 Jira 에 존재하는 모든 이슈가 후보가 된다.
     *
     * @param query      사용자 입력 텍스트 (자유 형식, JQL 안전화는 구현 측 책임)
     * @param maxResults 응답 상한 (Jira 의 기본 maxResults 는 50)
     * @return 매칭 이슈 목록. 검색 실패 시 빈 리스트.
     */
    List<JiraSearchHit> searchByText(String query, int maxResults);
}
