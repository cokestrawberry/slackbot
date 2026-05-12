package com.jirabot.slack.client;

import com.jirabot.slack.client.dto.ThreadActionResult;
import com.jirabot.slack.entity.IssueEntity;
import java.util.List;

public interface ThreadActionClassifier {

    /**
     * Classify a thread reply into an action on the parent issue.
     *
     * @param parentIssue the parent Jira issue
     * @param threadMessages the full thread conversation (oldest first)
     * @param userMessage the latest user message to classify
     * @return classified action result
     */
    ThreadActionResult classify(IssueEntity parentIssue, List<String> threadMessages, String userMessage);
}
