package com.jirabot.slack.service;

import com.jirabot.slack.client.dto.IntentResult;
import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.dto.IssueCreateCommand;
import java.util.concurrent.CompletableFuture;

public interface IssueCreateService {

    CompletableFuture<IssueCreateResult> createFromSlackText(IssueCreateCommand command);

    CompletableFuture<IssueCreateResult> createFromSlackText(IssueCreateCommand command, IntentResult intentHint);

    /**
     * Sonnet으로 분류만 수행 (Jira 생성 없이). 하위작업 생성 시 제목/SP 추출용.
     */
    IssueClassification classifyOnly(String rawText, IntentResult intentHint);
}
