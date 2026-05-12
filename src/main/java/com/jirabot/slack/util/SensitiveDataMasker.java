package com.jirabot.slack.util;

import java.util.regex.Pattern;

// STUDY: Slack에서 들어온 raw 입력은 intent_failures 테이블에 저장되어 운영자가 사후 분석한다.
//        사용자가 실수로 토큰/이메일/API key를 채팅에 붙여넣을 수 있어, DB로 흘러가기 전에
//        흔한 패턴은 마스킹한다. 완벽한 PII 스크러버는 아니지만 가장 위험한 leak 벡터 차단이 목적.
public final class SensitiveDataMasker {

    private static final Pattern EMAIL = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern ATLASSIAN_TOKEN = Pattern.compile("ATATT[A-Za-z0-9_\\-]{6,}");
    private static final Pattern GITHUB_TOKEN = Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}");
    private static final Pattern SLACK_BOT_TOKEN = Pattern.compile("xoxb-[A-Za-z0-9\\-]{10,}");
    private static final Pattern SLACK_USER_TOKEN = Pattern.compile("xoxp-[A-Za-z0-9\\-]{10,}");
    private static final Pattern BEARER_HEADER = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._\\-]{8,}");
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");

    private SensitiveDataMasker() {}

    public static String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String out = input;
        out = EMAIL.matcher(out).replaceAll("[email]");
        out = ATLASSIAN_TOKEN.matcher(out).replaceAll("[jira-token]");
        out = GITHUB_TOKEN.matcher(out).replaceAll("[github-token]");
        out = SLACK_BOT_TOKEN.matcher(out).replaceAll("[slack-bot-token]");
        out = SLACK_USER_TOKEN.matcher(out).replaceAll("[slack-user-token]");
        out = BEARER_HEADER.matcher(out).replaceAll("Bearer [redacted]");
        out = AWS_ACCESS_KEY.matcher(out).replaceAll("[aws-access-key]");
        return out;
    }
}
