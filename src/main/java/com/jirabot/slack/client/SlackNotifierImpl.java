package com.jirabot.slack.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

// STUDY: Slack chat.postMessage API로 스레드 댓글을 남긴다.
//        thread_ts 파라미터를 보내면 해당 메시지의 스레드에 댓글이 달린다.
@Component
public class SlackNotifierImpl implements SlackNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackNotifierImpl.class);

    private final WebClient slackWebClient;

    public SlackNotifierImpl(@Value("${slack.bot-token:}") String botToken) {
        // STUDY: WebClient를 빈으로 분리하지 않고 로컬 생성 — Slack API 호출은 이 클래스만 사용.
        this.slackWebClient = WebClient.builder()
                .baseUrl("https://slack.com/api")
                .defaultHeader("Authorization", "Bearer " + botToken)
                .defaultHeader("Content-Type", "application/json; charset=utf-8")
                .build();
    }

    @Override
    public void postThreadReply(String channel, String threadTs, String text) {
        try {
            String response = slackWebClient.post()
                    .uri("/chat.postMessage")
                    .bodyValue(Map.of(
                            "channel", channel,
                            "thread_ts", threadTs,
                            "text", text
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.debug("Slack reply sent channel={} threadTs={} response={}", channel, threadTs, response);
        } catch (Exception e) {
            // STUDY: 알림 실패가 Jira 이슈 생성 성공을 롤백하면 안 되므로 warn만 찍고 넘긴다.
            log.warn("Failed to send Slack thread reply: {}", e.toString());
        }
    }

    @Override
    public String getUserRealName(String userId) {
        try {
            // STUDY: Slack users.info API로 유저 정보 조회. real_name 필드가 실명.
            String response = slackWebClient.get()
                    .uri(uri -> uri.path("/users.info").queryParam("user", userId).build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
            if (node.path("ok").asBoolean(false)) {
                // STUDY: display_name이 Jira displayName과 일치할 가능성이 높다.
                //        display_name이 비어있으면 real_name fallback.
                String displayName = node.path("user").path("profile").path("display_name").asText("");
                if (!displayName.isBlank()) {
                    return displayName;
                }
                return node.path("user").path("real_name").asText(null);
            }
            log.warn("Slack users.info failed: {}", node.path("error").asText());
            return null;
        } catch (Exception e) {
            log.warn("Failed to get Slack user info: {}", e.toString());
            return null;
        }
    }

    @Override
    public List<String> getThreadMessages(String channel, String threadTs) {
        try {
            // STUDY: Slack conversations.replies API returns all messages in a thread.
            //        The first message is always the parent. Results are oldest-first by default.
            String response = slackWebClient.get()
                    .uri(uri -> uri.path("/conversations.replies")
                            .queryParam("channel", channel)
                            .queryParam("ts", threadTs)
                            .queryParam("limit", 50)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
            if (!node.path("ok").asBoolean(false)) {
                log.warn("Slack conversations.replies failed: {}", node.path("error").asText());
                return List.of();
            }
            var messages = new ArrayList<String>();
            for (var msg : node.path("messages")) {
                String text = msg.path("text").asText("");
                if (!text.isBlank()) {
                    messages.add(text);
                }
            }
            log.debug("Read {} thread messages from channel={} ts={}", messages.size(), channel, threadTs);
            return messages;
        } catch (Exception e) {
            log.warn("Failed to read thread messages: {}", e.toString());
            return List.of();
        }
    }

    @Override
    public void postMessage(String channel, String text) {
        try {
            slackWebClient.post()
                    .uri("/chat.postMessage")
                    .bodyValue(Map.of("channel", channel, "text", text))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.debug("Slack message sent channel={}", channel);
        } catch (Exception e) {
            log.warn("Failed to send Slack message: {}", e.toString());
        }
    }

    @Override
    public void sendDirectMessage(String userId, String text) {
        if (userId == null || userId.isBlank()) {
            log.warn("Slack DM skipped: userId is blank");
            return;
        }
        try {
            // STUDY: conversations.open 으로 user ID → IM 채널 ID 를 받는다. user ID 를 chat.postMessage 의 channel
            //        파라미터에 직접 넘기는 동작이 일부 워크스페이스/스코프 조합에서 channel_not_found 로 실패하는
            //        사례가 보고되어, 명시적으로 IM 채널을 여는 방식이 더 견고하다.
            String openResp = slackWebClient.post()
                    .uri("/conversations.open")
                    .bodyValue(Map.of("users", userId))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(openResp);
            if (!node.path("ok").asBoolean(false)) {
                log.warn("conversations.open failed for userId={}: {}", userId, node.path("error").asText());
                return;
            }
            String channelId = node.path("channel").path("id").asText(null);
            if (channelId == null || channelId.isBlank()) {
                log.warn("conversations.open returned no channel id for userId={}", userId);
                return;
            }
            postMessage(channelId, text);
        } catch (Exception e) {
            log.warn("Slack DM failed for userId={}: {}", userId, e.toString());
        }
    }
}
