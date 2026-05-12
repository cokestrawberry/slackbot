package com.jirabot.slack.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IntentResult(
        String intent,
        double confidence,
        Map<String, String> extracted,
        String rawInput
) {
    public static final double CONFIDENCE_THRESHOLD = 0.6;

    public boolean isActionable() {
        return confidence >= CONFIDENCE_THRESHOLD
                && intent != null
                && !"unknown".equals(intent);
    }

    public static IntentResult unknown(String rawInput) {
        return new IntentResult("unknown", 0, Map.of(), rawInput);
    }
}
