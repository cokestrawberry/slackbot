package com.jirabot.slack.client;

import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.client.dto.IntentResult;

public interface ClaudeApiClient {

    IssueClassification classify(String rawText);

    IssueClassification classify(String rawText, IntentResult intentHint);
}
