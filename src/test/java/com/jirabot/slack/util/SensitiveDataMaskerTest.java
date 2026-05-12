package com.jirabot.slack.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveDataMaskerTest {

    @Test
    void maskEmail() {
        assertThat(SensitiveDataMasker.mask("contact me at alice@example.com please"))
                .isEqualTo("contact me at [email] please");
    }

    @Test
    void maskAtlassianToken() {
        assertThat(SensitiveDataMasker.mask("token=ATATT3xFfGF0abcDEF12345678"))
                .contains("[jira-token]")
                .doesNotContain("ATATT3xFfGF0");
    }

    @Test
    void maskGithubToken() {
        assertThat(SensitiveDataMasker.mask("ghp_abcdefghijklmnopqrstuvwxyz0123456789"))
                .isEqualTo("[github-token]");
    }

    @Test
    void maskSlackBotToken() {
        // STUDY: 실제 토큰 형태로 보이는 문자열은 GitHub push-protection이 차단하므로
        //        프리픽스와 일부 자리수만 유지하고 의도적으로 비-secret처럼 분리해서 만든다.
        //        SLACK_BOT_TOKEN regex가 [A-Za-z0-9-]만 허용하므로 underscore 없이 작성한다.
        String fakeToken = "xoxb" + "-" + "NotARealTokenJustForTestingABCdef";
        assertThat(SensitiveDataMasker.mask(fakeToken)).isEqualTo("[slack-bot-token]");
    }

    @Test
    void maskBearerHeader() {
        assertThat(SensitiveDataMasker.mask("Authorization: Bearer abcdefghijklmnopqrstuv"))
                .isEqualTo("Authorization: Bearer [redacted]");
    }

    @Test
    void leavesPlainTextAlone() {
        assertThat(SensitiveDataMasker.mask("로그인 페이지에서 500 에러 발생"))
                .isEqualTo("로그인 페이지에서 500 에러 발생");
    }

    @Test
    void handlesNull() {
        assertThat(SensitiveDataMasker.mask(null)).isNull();
    }
}
