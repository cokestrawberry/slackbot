package com.jirabot.slack.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JiraCreateRequest(Fields fields) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Fields(
            ProjectRef project,
            String summary,
            IssueTypeRef issuetype,
            Map<String, Object> description,
            List<String> labels,
            // STUDY: Jira Story Points는 커스텀 필드. Team-managed 프로젝트 기본값은 customfield_10016.
            //        @JsonProperty로 Java 필드명과 JSON 키를 매핑한다.
            @com.fasterxml.jackson.annotation.JsonProperty("customfield_10016")
            Double storyPoint
    ) {}

    public record ProjectRef(String key) {}

    public record IssueTypeRef(String name) {}
}
