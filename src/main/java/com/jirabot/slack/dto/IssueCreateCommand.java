package com.jirabot.slack.dto;

public record IssueCreateCommand(
        String rawText,
        String slackUserId,
        String channel,
        String eventTs
) {
    public static IssueCreateCommand from(SlackEventInner event) {
        return new IssueCreateCommand(event.text(), event.user(), event.channel(), event.ts());
    }

    public static IssueCreateCommand from(SlackEventInner event, String cleanedText) {
        return new IssueCreateCommand(cleanedText, event.user(), event.channel(), event.ts());
    }
}
