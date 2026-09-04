package com.notification.infrastructure.config;
// PRD: F4-1 → docs/prd/F4.md, docs/DECISIONS.md D-002

import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@EnableAsync
@Configuration
public class AsyncConfig {

    /**
     * 단건 실시간 발송. 이벤트 핸들러 전용. 목표는 지연 최소화.
     *
     * 거부 정책이 CallerRuns가 아닌 이유: 제출자가 http-nio-* 스레드다.
     * CallerRuns를 쓰면 톰캣 스레드가 발송을 직접 하게 돼 API 전체가 막힌다.
     *
     * AbortPolicy도 아니다. 제출 지점이 afterCommit 콜백이라 여기서 던지면
     * <b>커밋은 됐는데 HTTP는 500</b>이 나간다. 행은 PENDING이라 스케줄러가 1분 내 회수하므로
     * 거부는 유실이 아니라 지연이다. 그래서 로그만 남기고 버린다. → docs/DECISIONS.md D-013
     */
    /**
     * 거부를 삼키지 않고 기록만 한다. 회수 경로(PENDING → 스케줄러)가 있으므로 버려도 안전하다.
     * 회수 경로 없는 상태를 만들지 않는다는 규칙은 지켜진다.
     */
    private ThreadPoolExecutor.DiscardPolicy discardWithLog(String poolName) {
        return new ThreadPoolExecutor.DiscardPolicy() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                log.warn("[{}] 워커풀 포화로 즉시 발송 거부. PENDING으로 남아 스케줄러가 회수한다. "
                        + "active={}, queue={}", poolName, e.getActiveCount(), e.getQueue().size());
            }
        };
    }

    @Bean(name = "realtimeExecutor")
    public Executor realtimeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("notification-rt-");
        executor.setRejectedExecutionHandler(discardWithLog("realtime"));
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
        executor.setCorePoolSize(15);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("notification-batch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
