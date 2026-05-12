package com.jirabot.slack.repository;

import com.jirabot.slack.entity.IntentFailureEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntentFailureRepository extends JpaRepository<IntentFailureEntity, Long> {
}
