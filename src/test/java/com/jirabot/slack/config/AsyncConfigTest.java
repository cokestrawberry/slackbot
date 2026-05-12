package com.jirabot.slack.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = {AsyncConfig.class, AsyncConfigTest.Probe.class})
@TestPropertySource(properties = {
        "spring.main.web-application-type=none",
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
})
class AsyncConfigTest {

    @Autowired
    @Qualifier(AsyncConfig.SLACK_EXECUTOR)
    private ThreadPoolTaskExecutor executor;

    @Autowired
    private Probe probe;

    @Test
    void slackExecutorBeanHasExpectedPoolSettings() {
        assertThat(executor.getCorePoolSize()).isEqualTo(4);
        assertThat(executor.getMaxPoolSize()).isEqualTo(10);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("slack-async-");
    }

    @Test
    void asyncMethodRunsOnSlackExecutorThread() throws ExecutionException, InterruptedException {
        String threadName = probe.capture().get();
        assertThat(threadName).startsWith("slack-async-");
    }

    @Test
    void rejectionPolicyAbortsAndThrowsWhenSaturated() throws InterruptedException {
        ThreadPoolExecutor underlying = executor.getThreadPoolExecutor();
        int capacity = underlying.getMaximumPoolSize() + underlying.getQueue().remainingCapacity();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(underlying.getMaximumPoolSize());

        for (int i = 0; i < capacity; i++) {
            underlying.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() -> underlying.execute(() -> { }))
                    .isInstanceOf(RejectedExecutionException.class)
                    .hasMessageContaining("saturated");
        } finally {
            release.countDown();
        }
    }

    @Component
    static class Probe {
        @Async(AsyncConfig.SLACK_EXECUTOR)
        public java.util.concurrent.Future<String> capture() {
            return new AsyncResult<>(Thread.currentThread().getName());
        }
    }
}
