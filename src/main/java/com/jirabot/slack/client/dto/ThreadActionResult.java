package com.jirabot.slack.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ThreadActionResult(
        String action,
        double confidence,
        Map<String, String> extracted,
        String rawInput
) {
    public static final double CONFIDENCE_THRESHOLD = 0.6;

    public boolean isActionable() {
        return confidence >= CONFIDENCE_THRESHOLD
                && action != null
                && !"unknown".equals(action);
    }

    public static ThreadActionResult unknown(String rawInput) {
        return new ThreadActionResult("unknown", 0, Map.of(), rawInput);
    }
}
