package com.jirabot.slack.service;

public record IssueCreateResult(
        boolean success,
        String issueKey,
        String issueUrl,
        String errorMessage
) {
    public static IssueCreateResult ok(String key, String url) {
        return new IssueCreateResult(true, key, url, null);
    }

    public static IssueCreateResult failure(String message) {
        return new IssueCreateResult(false, null, null, message);
    }
}
