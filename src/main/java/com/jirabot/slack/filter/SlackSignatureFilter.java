package com.jirabot.slack.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

// STUDY: Spring Security 필터 체인에 커스텀 필터를 꽂으려면 OncePerRequestFilter 를 상속하고
// SecurityConfig 에서 addFilterBefore/After 로 등록. @Component 빈이어야 테스트/주입이 쉬움.
@Component
public class SlackSignatureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SlackSignatureFilter.class);
    private static final String SLACK_PATH_PREFIX = "/api/slack/";
    private static final String SIGNATURE_HEADER = "X-Slack-Signature";
    private static final String TIMESTAMP_HEADER = "X-Slack-Request-Timestamp";
    private static final String SIGNATURE_VERSION = "v0";
    private static final long MAX_CLOCK_SKEW_SECONDS = 300L;
    private static final String HMAC_ALGO = "HmacSHA256";

    private final String signingSecret;
    private final Clock clock;

    public SlackSignatureFilter(
            @Value("${slack.signing-secret:}") String signingSecret,
            Clock clock) {
        this.signingSecret = signingSecret;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(SLACK_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (!StringUtils.hasText(signingSecret)) {
            log.error("slack.signing-secret is not configured — refusing Slack request to {}", request.getRequestURI());
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "signing secret not configured");
            return;
        }

        String signature = request.getHeader(SIGNATURE_HEADER);
        String timestampHeader = request.getHeader(TIMESTAMP_HEADER);
        if (!StringUtils.hasText(signature) || !StringUtils.hasText(timestampHeader)) {
            reject(response, "missing slack signature headers");
            return;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            reject(response, "non-numeric slack timestamp");
            return;
        }

        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - timestamp) > MAX_CLOCK_SKEW_SECONDS) {
            reject(response, "slack timestamp outside 5-minute replay window");
            return;
        }

        byte[] rawBody = resolveRawBody(request);
        String expected = computeSignature(timestamp, rawBody);

        // STUDY: MessageDigest.isEqual 은 길이가 같으면 모든 바이트를 비교해 timing-attack 을 방어.
        // String.equals 는 불일치 바이트에서 바로 리턴하므로 사용 금지.
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = signature.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, providedBytes)) {
            log.warn("slack signature mismatch: uri={} bodyLen={} expected={} provided={}",
                    request.getRequestURI(), rawBody.length, expected, signature);
            reject(response, "slack signature mismatch");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private byte[] resolveRawBody(HttpServletRequest request) throws IOException {
        Object cached = request.getAttribute(CachedBodyFilter.RAW_BODY_ATTRIBUTE);
        if (cached instanceof byte[] bytes) {
            return bytes;
        }
        return request.getInputStream().readAllBytes();
    }

    private String computeSignature(long timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            mac.update((SIGNATURE_VERSION + ":" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
            byte[] digest = mac.doFinal(rawBody);
            return SIGNATURE_VERSION + "=" + toHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private void reject(HttpServletResponse response, String reason) throws IOException {
        log.warn("slack signature check failed: {}", reason);
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "invalid slack signature");
    }
}
