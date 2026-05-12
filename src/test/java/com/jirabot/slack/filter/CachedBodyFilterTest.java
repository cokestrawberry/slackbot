package com.jirabot.slack.filter;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CachedBodyFilterTest {

    @Test
    void wrapsSlackRequestAndExposesRawBodyAttribute() throws Exception {
        String body = "{\"type\":\"event_callback\"}";
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/slack/event");
        req.setRequestURI("/api/slack/event");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        new CachedBodyFilter().doFilter(req, res, chain);

        ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);
        Mockito.verify(chain).doFilter(captor.capture(), Mockito.eq(res));
        HttpServletRequest forwarded = captor.getValue();

        assertThat(forwarded).isInstanceOf(CachedBodyHttpServletRequest.class);
        byte[] rawBody = (byte[]) forwarded.getAttribute(CachedBodyFilter.RAW_BODY_ATTRIBUTE);
        assertThat(new String(rawBody, StandardCharsets.UTF_8)).isEqualTo(body);

        String first = new String(forwarded.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String second = new String(forwarded.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(first).isEqualTo(body);
        assertThat(second).isEqualTo(body);
    }

    @Test
    void skipsNonSlackPaths() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        new CachedBodyFilter().doFilter(req, res, chain);

        Mockito.verify(chain).doFilter(req, res);
    }
}
