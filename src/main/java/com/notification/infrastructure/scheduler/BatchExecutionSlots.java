package com.notification.infrastructure.scheduler;
// PRD: F4-1, F5-3 → docs/PLAN.md P0-c, ARCHITECTURE-GUIDE.md §15.8

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * 이 JVM이 <b>동시에 실행할 수 있는 배치 발송 수</b>를 나타내는 로컬 슬롯.
 *
 * <p>왜 필요한가: 예전에는 "큐 빈자리 + 노는 스레드"만큼 DB에서 선점했다. 큐 200칸이면 실행할 수 없는
 * 200건까지 PROCESSING으로 바꿔 놓고 메모리 큐에 쌓아 두는 셈이고, 그 작업들은 <b>아직 시작도 못 한 채</b>
 * lease를 소모한다. 큐에서 오래 기다리다 lease가 끝나면 다른 노드가 회수해 같은 알림을 다시 잡는다.
 * 그래서 <b>실행할 수 있는 만큼만 선점</b>한다. 못 가져간 잔량은 메모리가 아니라 DB에 남아,
 * 여유가 생긴 아무 노드나 다음 scan에서 집어 간다.
 *
 * <p>이 세마포어는 <b>수용량 제어</b>일 뿐 중복 방지 장치가 아니다. N대 사이의 소유권은 DB의
 * claim/token이 정한다. 로컬 락으로 다중 인스턴스 중복을 막지 않는다는 규칙과 충돌하지 않는다. (RULES)
 */
@Slf4j
@Component
public class BatchExecutionSlots {

    private final Semaphore slots;
    private final int capacity;

    @Autowired
    public BatchExecutionSlots(@Qualifier("batchExecutor") ThreadPoolTaskExecutor batchExecutor) {
        // 동시에 실행 중이거나 곧 실행될 작업 수 = 스레드 + 큐. 초기화 직후라 큐는 비어 있어 정확하다.
        // 큐를 짧게 유지하는 책임은 AsyncConfig에 있고, 여기서는 그 합을 그대로 상한으로 삼는다.
        this(batchExecutor.getMaxPoolSize()
                + batchExecutor.getThreadPoolExecutor().getQueue().remainingCapacity());
        log.info("[실행 슬롯] 배치 동시 수용량 {}건 (스레드 {} + 큐 {})", capacity,
                batchExecutor.getMaxPoolSize(), capacity - batchExecutor.getMaxPoolSize());
    }

    /** 수용량을 직접 지정한다. 테스트가 executor 내부 구조를 흉내 내지 않도록 분리했다. */
    BatchExecutionSlots(int capacity) {
        this.capacity = capacity;
        this.slots = new Semaphore(capacity);
    }

    /**
     * 최대 {@code max}개까지 확보한다. 남은 슬롯이 부족하면 확보한 만큼만 돌려준다(부분 성공).
     *
     * <p>전부-아니면-전무로 잡지 않는 이유: 슬롯 3개가 남았는데 100건을 요청했다고 0을 돌려주면
     * 놀고 있는 워커가 생긴다. 개별 획득은 원자적이므로 총량이 capacity를 넘지 않는다는 보장은 유지된다.
     *
     * @return 실제로 확보한 슬롯 수. 반드시 같은 수만큼 {@link #release(int)}로 돌려줘야 한다
     */
    public int tryAcquireUpTo(int max) {
        int acquired = 0;
        while (acquired < max && slots.tryAcquire()) {
            acquired++;
        }
        return acquired;
    }

    /** 확보한 슬롯을 돌려준다. worker 완료·예외·제출 거절·DB 실패 등 <b>모든 종료 경로</b>에서 호출한다. */
    public void release(int count) {
        if (count <= 0) return;
        slots.release(count);
        if (slots.availablePermits() > capacity) {   // 반환이 획득보다 많으면 회계 버그다
            log.error("[실행 슬롯] 반환 초과. available={}, capacity={}", slots.availablePermits(), capacity);
        }
    }

    /** 현재 남은 슬롯 수. 관측·테스트용이며 이 값으로 선점량을 정하지 않는다(정하는 건 실제 획득 결과다). */
    public int available() {
        return slots.availablePermits();
    }

    public int capacity() {
        return capacity;
    }
}
