package com.jirabot.slack.controller;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// STUDY: Slack은 3초 내 200 응답을 못 받으면 같은 event를 최대 3회 재전송한다 (event.ts/event_id 동일).
// 컨트롤러가 이미 200을 즉시 반환하므로 정상 흐름에서는 재전송이 드물지만, slackTaskExecutor 포화·
// 외부 API 지연 시 재시도가 race window를 만들어 동일 이슈가 두 번 생성될 수 있다.
// 프로세스 로컬 in-memory 캐시로 (channel, ts) 페어를 짧은 윈도우 동안 기억해 중복 처리를 차단한다.
@Component
public class SlackEventDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(SlackEventDeduplicator.class);
    private static final long TTL_MILLIS = 60_000L;
    private static final int CLEANUP_THRESHOLD = 1024;

    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();

    public boolean isDuplicate(String channel, String ts) {
        if (channel == null || ts == null) {
            return false;
        }
        String key = channel + ":" + ts;
        long now = System.currentTimeMillis();
        Long prev = seen.putIfAbsent(key, now);
        maybeCleanup(now);
        if (prev == null) {
            return false;
        }
        if (now - prev < TTL_MILLIS) {
            log.info("Duplicate Slack event suppressed channel={} ts={} ageMs={}", channel, ts, now - prev);
            return true;
        }
        seen.put(key, now);
        return false;
    }

    private void maybeCleanup(long now) {
        if (seen.size() < CLEANUP_THRESHOLD) {
            return;
        }
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now - entry.getValue() >= TTL_MILLIS) {
                it.remove();
            }
        }
    }
}
