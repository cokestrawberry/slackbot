package com.jirabot.slack.client;

import com.jirabot.slack.client.dto.IntentResult;

public interface IntentClassifier {
    IntentResult classify(String rawText);
}
