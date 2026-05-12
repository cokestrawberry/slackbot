package com.jirabot.slack.controller;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// STUDY: @RestController = @Controller + @ResponseBody — 메서드 리턴값이 JSON으로 직렬화됨.
// STUDY: Map.of()로 만든 불변 Map은 Jackson이 자동으로 JSON object로 직렬화.
// Phase 1 완료 기준: `curl localhost:8080/health` → 200 "UP"
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "slackbot-server",
                "timestamp", Instant.now().toString()
        );
    }
}
