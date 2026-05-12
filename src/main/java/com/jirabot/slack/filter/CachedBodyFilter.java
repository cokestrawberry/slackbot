package com.jirabot.slack.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// STUDY: OncePerRequestFilter 는 dispatcher 재진입(예: forward) 시 중복 실행을 방지하는
// Spring Security/Web 공용 기반 필터. Filter 를 직접 상속하면 forward/include 에서 다시 호출될 수 있음.
@Component
public class CachedBodyFilter extends OncePerRequestFilter {

    public static final String RAW_BODY_ATTRIBUTE = "slack.rawBody";
    private static final String SLACK_PATH_PREFIX = "/api/slack/";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(SLACK_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
        cached.setAttribute(RAW_BODY_ATTRIBUTE, cached.getCachedBody());
        filterChain.doFilter(cached, response);
    }
}
