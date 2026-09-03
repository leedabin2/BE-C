package com.notification.infrastructure.config;
// PRD: F4-1 → docs/prd/F4.md, docs/DECISIONS.md D-002

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 발송 워커풀. 실시간과 배치를 <b>일부러 나눈다.</b>
 *
 * 큐를 공유하면 대량 발송 30만 건이 큐를 점유해 방금 결제한 사용자의 알림이 뒤로 밀린다.
 * 풀이 갈리면 한쪽이 포화돼도 다른 쪽 지연은 그대로다.
 *
 * ⚠️ ThreadPoolExecutor 증설 순서는 직관과 반대다: core가 차면 스레드를 늘리는 게 아니라
 * 큐에 쌓고, 큐가 꽉 차야 max까지 늘린다. 외부 I/O 대기가 병목이므로 큐를 짧게, core를 크게 잡는다.
 */
@EnableAsync
@Configuration
public class AsyncConfig {

    /**
     * 단건 실시간 발송. 이벤트 핸들러 전용. 목표는 지연 최소화.
     *
     * 거부 정책이 기본(AbortPolicy)인 이유: 제출자가 http-nio-* 스레드다.
     * CallerRuns를 쓰면 톰캣 스레드가 발송을 직접 하게 돼 API 전체가 막힌다.
     * 거부돼도 행은 PENDING이라 스케줄러가 1분 내 회수한다 — 유실이 아니라 지연이다.
     */
    @Bean(name = "realtimeExecutor")
    public Executor realtimeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("notification-rt-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 배치 재처리 발송. 스케줄러 전용. 목표는 처리량 최대화.
     *
     * CallerRunsPolicy: 큐가 꽉 차면 제출자(scheduling-*)가 직접 실행한다 → 제출 속도가
     * 저절로 느려지는 백프레셔. 버리지 않는다.
     * 용량(50+200=250) > 배치 크기(100)라 평상시엔 발동하지 않는 안전판이다.
     */
    @Bean(name = "batchExecutor")
    public Executor batchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("notification-batch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
