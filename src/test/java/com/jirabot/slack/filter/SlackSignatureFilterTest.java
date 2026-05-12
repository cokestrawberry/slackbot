package com.jirabot.slack.filter;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SlackSignatureFilterTest {

    private static final String SECRET = "test-signing-secret";
    private static final long FIXED_NOW = 1_700_000_000L;
    private static final String PATH = "/api/slack/event";

    private SlackSignatureFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(FIXED_NOW), ZoneOffset.UTC);
        filter = new SlackSignatureFilter(SECRET, clock);
        chain = Mockito.mock(FilterChain.class);
    }

    @Test
    void validSignatureProceedsDownstream() throws Exception {
        String body = "{\"type\":\"event_callback\"}";
        MockHttpServletRequest req = newRequest(body);
        req.addHeader("X-Slack-Request-Timestamp", String.valueOf(FIXED_NOW));
        req.addHeader("X-Slack-Signature", sign(FIXED_NOW, body, SECRET));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        Mockito.verify(chain).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void wrongSignatureReturns403() throws Exception {
        String body = "{\"type\":\"event_callback\"}";
        MockHttpServletRequest req = newRequest(body);
        req.addHeader("X-Slack-Request-Timestamp", String.valueOf(FIXED_NOW));
        req.addHeader("X-Slack-Signature", "v0=deadbeef");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        Mockito.verifyNoInteractions(chain);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void missingSignatureHeaderReturns403() throws Exception {
        MockHttpServletRequest req = newRequest("{}");
        req.addHeader("X-Slack-Request-Timestamp", String.valueOf(FIXED_NOW));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        Mockito.verifyNoInteractions(chain);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void missingTimestampHeaderReturns403() throws Exception {
        MockHttpServletRequest req = newRequest("{}");
        req.addHeader("X-Slack-Signature", "v0=abc");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        Mockito.verifyNoInteractions(chain);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void nonNumericTimestampReturns403() throws Exception {
        MockHttpServletRequest req = newRequest("{}");
        req.addHeader("X-Slack-Request-Timestamp", "not-a-number");
        req.addHeader("X-Slack-Signature", "v0=abc");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        Mockito.verifyNoInteractions(chain);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void staleTimestampReturns403() throws Exception {
        String body = "{}";
        long stale = FIXED_NOW - 400L;
        MockHttpServletRequest req = newRequest(body);
        req.addHeader("X-Slack-Request-Timestamp", String.valueOf(stale));
        req.addHeader("X-Slack-Signature", sign(stale, body, SECRET));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        Mockito.verifyNoInteractions(chain);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void futureTimestampBeyondWindowReturns403() throws Exception {
        String body = "{}";
        long future = FIXED_NOW + 400L;
        MockHttpServletRequest req = newRequest(body);
        req.addHeader("X-Slack-Request-Timestamp", String.valueOf(future));
        req.addHeader("X-Slack-Signature", sign(future, body, SECRET));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        Mockito.verifyNoInteractions(chain);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void emptyBodyStillHashesCorrectly() throws Exception {
        String body = "";
        MockHttpServletRequest req = newRequest(body);
        req.addHeader("X-Slack-Request-Timestamp", String.valueOf(FIXED_NOW));
        req.addHeader("X-Slack-Signature", sign(FIXED_NOW, body, SECRET));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        Mockito.verify(chain).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void emptySigningSecretReturns503() throws Exception {
        SlackSignatureFilter unconfigured = new SlackSignatureFilter(
                "",
                Clock.fixed(Instant.ofEpochSecond(FIXED_NOW), ZoneOffset.UTC));
        MockHttpServletRequest req = newRequest("{}");
        req.addHeader("X-Slack-Request-Timestamp", String.valueOf(FIXED_NOW));
        req.addHeader("X-Slack-Signature", "v0=abc");
        MockHttpServletResponse res = new MockHttpServletResponse();

        unconfigured.doFilter(req, res, chain);

        Mockito.verifyNoInteractions(chain);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void nonSlackPathSkipsFilter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        Mockito.verify(chain).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    private MockHttpServletRequest newRequest(String body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", PATH);
        req.setRequestURI(PATH);
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        req.setContentType("application/json");
        return req;
    }

    private static String sign(long timestamp, String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
        byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return "v0=" + sb;
    }
}
