package com.notification.infrastructure.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("배치 실행 슬롯")
class BatchExecutionSlotsTest {

    private BatchExecutionSlots slotsOf(int capacity) {
        return new BatchExecutionSlots(capacity);
    }

    @Test
    @DisplayName("요청보다 적게 남았으면 남은 만큼만 확보한다 (0을 돌려주면 워커가 논다)")
    void 부분_확보를_허용한다() {
        BatchExecutionSlots slots = slotsOf(5);

        assertThat(slots.tryAcquireUpTo(3)).isEqualTo(3);
        assertThat(slots.tryAcquireUpTo(10)).as("남은 2개만").isEqualTo(2);
        assertThat(slots.tryAcquireUpTo(1)).as("고갈되면 0").isZero();
        assertThat(slots.available()).isZero();
    }

    @Test
    @DisplayName("반환한 만큼만 다시 확보된다")
    void 반환하면_다시_확보된다() {
        BatchExecutionSlots slots = slotsOf(4);
        slots.tryAcquireUpTo(4);

        slots.release(2);

        assertThat(slots.available()).isEqualTo(2);
        assertThat(slots.tryAcquireUpTo(4)).isEqualTo(2);
    }

    @Test
    @DisplayName("0 이하 반환은 무시한다 — '남는 슬롯 반환'을 호출자가 분기 없이 쓰게 한다")
    void 음수_반환은_무시한다() {
        BatchExecutionSlots slots = slotsOf(2);
        slots.tryAcquireUpTo(2);

        slots.release(0);
        slots.release(-3);

        assertThat(slots.available()).isZero();
    }

    @Test
    @DisplayName("여러 스레드가 동시에 확보해도 총합이 용량을 넘지 않는다")
    void 동시_확보가_용량을_넘지_않는다() throws Exception {
        int capacity = 16;
        BatchExecutionSlots slots = slotsOf(capacity);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger total = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    total.addAndGet(slots.tryAcquireUpTo(10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(total.get()).as("확보 총합은 용량과 정확히 같아야 한다").isEqualTo(capacity);
        assertThat(slots.available()).isZero();
    }
}
