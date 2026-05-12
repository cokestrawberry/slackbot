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
            @com.fasterxml.jackson.annotation.JsonProperty("customfield_10016")
            Double storyPoint,
            // STUDY: reporter/assignee는 accountId로 지정. Jira Cloud에서는 name이 아닌 accountId 필수.
            AccountRef reporter,
            AccountRef assignee
    ) {}

    public record ProjectRef(String key) {}

    public record IssueTypeRef(String name) {}

    public record AccountRef(String id) {}
}
