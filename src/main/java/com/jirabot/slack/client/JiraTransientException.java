package com.jirabot.slack.client;

// STUDY: @Retryable(retryFor=...)는 지정된 예외 타입만 재시도한다.
// 4xx는 영구 실패(JiraApiException)로, 5xx/네트워크 오류는 JiraTransientException으로 구분.
public class JiraTransientException extends RuntimeException {

    public JiraTransientException(String message) {
        super(message);
    }

    public JiraTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
