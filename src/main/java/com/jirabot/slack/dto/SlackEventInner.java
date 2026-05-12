package com.jirabot.slack.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackEventInner(
        String type,
        String user,
        String text,
        String channel,
        String ts,
        String subtype,
        String bot_id,
        // STUDY: thread_ts는 스레드 댓글일 때만 존재. 부모 메시지의 ts를 가리킨다.
        //        null이면 최상위 메시지, 값이 있으면 스레드 내 댓글.
        String thread_ts
) {
    public boolean isFromHuman() {
        // STUDY: 봇이 자기 메시지를 다시 처리하지 않도록 bot_id/subtype으로 걸러낸다.
        return subtype == null && bot_id == null && user != null && !user.isBlank();
    }
}
