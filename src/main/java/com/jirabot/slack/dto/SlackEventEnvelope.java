package com.jirabot.slack.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackEventEnvelope(
        String type,
        String challenge,
        SlackEventInner event
) {
    public static final String URL_VERIFICATION = "url_verification";
    public static final String EVENT_CALLBACK = "event_callback";
}
