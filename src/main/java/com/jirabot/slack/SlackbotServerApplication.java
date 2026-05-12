package com.jirabot.slack;

import com.jirabot.slack.service.JiraSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

// STUDY: @SpringBootApplication = @Configuration + @EnableAutoConfiguration + @ComponentScan.
// STUDY: @EnableScheduling turns on @Scheduled beans.
// STUDY: @EnableRetry turns on @Retryable — Spring Retry로 외부 API 실패 자동 재시도.
@SpringBootApplication
@EnableScheduling
@EnableRetry
public class SlackbotServerApplication {

	private static final Logger log = LoggerFactory.getLogger(SlackbotServerApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(SlackbotServerApplication.class, args);
	}

	// STUDY: ApplicationRunner는 앱 기동 완료 후 1회 실행된다. 시작 시 Jira → DB 동기화.
	@Bean
	ApplicationRunner initialSync(JiraSyncService jiraSyncService) {
		return args -> {
			try {
				String result = jiraSyncService.syncActiveSprint();
				log.info("Initial Jira sync: {}", result);
			} catch (Exception e) {
				log.warn("Initial Jira sync failed (non-fatal): {}", e.toString());
			}
		};
	}
}
