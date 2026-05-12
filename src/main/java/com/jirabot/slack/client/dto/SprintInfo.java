package com.jirabot.slack.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SprintInfo(
        int id,
        String name,
        String state,
        String startDate,
        String endDate
) {}
