package com.jirabot.slack.controller;

import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.UserMappingRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// STUDY: 내부 관리용 API. Slack 유저 ↔ Jira displayName 매핑을 등록/조회한다.
//        scripts/register-user-mapping.sh 에서 호출.
@RestController
@RequestMapping("/api/user-mappings")
public class UserMappingController {

    private final UserMappingRepository repository;

    public UserMappingController(UserMappingRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<UserMappingEntity> listAll() {
        return repository.findAll();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String slackUserId = body.get("slackUserId");
        String slackDisplayName = body.get("slackDisplayName");
        String jiraDisplayName = body.get("jiraDisplayName");

        if (slackUserId == null || jiraDisplayName == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "slackUserId and jiraDisplayName are required"));
        }

        var existing = repository.findBySlackUserId(slackUserId);
        if (existing.isPresent()) {
            var entity = existing.get();
            entity.setJiraDisplayName(jiraDisplayName);
            if (slackDisplayName != null) entity.setSlackDisplayName(slackDisplayName);
            repository.save(entity);
            return ResponseEntity.ok(Map.of("status", "updated",
                    "slackUserId", slackUserId, "jiraDisplayName", jiraDisplayName));
        }

        repository.save(new UserMappingEntity(slackUserId, slackDisplayName, jiraDisplayName));
        return ResponseEntity.ok(Map.of("status", "created",
                "slackUserId", slackUserId, "jiraDisplayName", jiraDisplayName));
    }
}
