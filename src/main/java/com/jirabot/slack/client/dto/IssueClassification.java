package com.jirabot.slack.client.dto;

public record IssueClassification(
        IssueType type,
        int storyPoint,
        String title,
        String summary
) {
    public enum IssueType { BUG, FEATURE, OTHER }

    public static IssueClassification fallback(String rawText) {
        String safe = rawText == null ? "" : rawText.strip();
        String title = safe.length() > 80 ? safe.substring(0, 80) : safe;
        if (title.isBlank()) {
            title = "Untitled issue from Slack";
        }
        return new IssueClassification(IssueType.OTHER, 3, title, safe);
    }
}
