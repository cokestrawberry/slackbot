package com.jirabot.slack.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ScrumReportService {

    // STUDY: 스프린트와 백로그 섹션을 각각 별도 Slack 메시지로 보내기 위해 리스트로 반환.
    //        하나로 합치면 길이 초과로 Slack 이 자동 분할해 가독성이 떨어졌다.
    CompletableFuture<List<String>> generateReport();

    CompletableFuture<String> generateMyReport(String slackUserId);

    CompletableFuture<String> generateMemberReport(String memberName);

    CompletableFuture<String> generateStatisticsReport();
}
