package com.jirabot.slack.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SlackEventDeduplicatorTest {

    @Test
    void firstSightingIsNotDuplicate() {
        SlackEventDeduplicator dedup = new SlackEventDeduplicator();
        assertThat(dedup.isDuplicate("C1", "1700000000.000100")).isFalse();
    }

    @Test
    void secondSightingWithinWindowIsDuplicate() {
        SlackEventDeduplicator dedup = new SlackEventDeduplicator();
        dedup.isDuplicate("C1", "1700000000.000100");
        assertThat(dedup.isDuplicate("C1", "1700000000.000100")).isTrue();
    }

    @Test
    void distinctChannelsDoNotInterfere() {
        SlackEventDeduplicator dedup = new SlackEventDeduplicator();
        dedup.isDuplicate("C1", "1700000000.000100");
        assertThat(dedup.isDuplicate("C2", "1700000000.000100")).isFalse();
    }

    @Test
    void distinctTsDoNotInterfere() {
        SlackEventDeduplicator dedup = new SlackEventDeduplicator();
        dedup.isDuplicate("C1", "1700000000.000100");
        assertThat(dedup.isDuplicate("C1", "1700000000.000200")).isFalse();
    }

    @Test
    void nullValuesAreTreatedAsNonDuplicate() {
        SlackEventDeduplicator dedup = new SlackEventDeduplicator();
        assertThat(dedup.isDuplicate(null, "1.0")).isFalse();
        assertThat(dedup.isDuplicate("C1", null)).isFalse();
    }
}
