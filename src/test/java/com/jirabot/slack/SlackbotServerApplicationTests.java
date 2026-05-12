package com.jirabot.slack;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// STUDY: @SpringBootTest 는 전체 애플리케이션 컨텍스트를 부팅한다. JPA/DataSource 가 포함되어 있으면
// 실제 DB 연결이 필요. 테스트에서는 H2 in-memory 로 대체하기 위해 @ActiveProfiles("test") 로
// application-test.yml 을 활성화.
@SpringBootTest
@ActiveProfiles("test")
class SlackbotServerApplicationTests {

    @Test
    void contextLoads() {
        // 컨텍스트 부팅 성공만 검증. 실패 시 Bean 구성/설정 문제를 조기 감지.
    }
}
