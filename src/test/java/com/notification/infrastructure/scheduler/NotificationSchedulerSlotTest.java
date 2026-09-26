package com.notification.infrastructure.scheduler;

import com.notification.application.service.DispatchStateService;
import com.notification.application.service.DispatchStateService.ClaimedNotification;
import com.notification.application.service.NotificationDispatchService;
import com.notification.domain.NotificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * P0-c 슬롯 회계. <b>확보한 실행 슬롯은 모든 종료 경로에서 정확히 한 번 반환된다.</b>
 *
 * <p>슬롯이 새면 두 방향 모두로 망가진다. 반환을 빠뜨리면 그 JVM은 점점 적게 가져가다 결국 아무것도
 * 못 가져가고(조용한 정지), 두 번 반환하면 실행할 수 없는 양을 선점해 lease만 태운다.
 * DB 없이 회계만 검증한다 — 실제 발송·반환은 통합 테스트가 본다.
 */
@DisplayName("배치 스케줄러 실행 슬롯 회계")
class NotificationSchedulerSlotTest {

    private static final int CAPACITY = 3;

    private NotificationDispatchService dispatchService;
    private DispatchStateService dispatchStateService;
    private ThreadPoolTaskExecutor batchExecutor;
    private BatchExecutionSlots slots;
    private NotificationScheduler scheduler;

    @BeforeEach
    void setUp() {
        dispatchService = mock(NotificationDispatchService.class);
        dispatchStateService = mock(DispatchStateService.class);
        batchExecutor = mock(ThreadPoolTaskExecutor.class);

        slots = new BatchExecutionSlots(CAPACITY);
        scheduler = new NotificationScheduler(dispatchService, dispatchStateService, batchExecutor, slots);
        ReflectionTestUtils.setField(scheduler, "retryBatchSize", 100);
        ReflectionTestUtils.setField(scheduler, "retryMaxRounds", 20);
        ReflectionTestUtils.setField(scheduler, "retryRoundBudgetMs", 500L);
    }

    private ClaimedNotification workItem(long id) {
        return new ClaimedNotification(id, "token-" + id, NotificationStatus.PENDING, null);
    }

    private List<ClaimedNotification> workItems(int count) {
        return IntStream.range(0, count).mapToObj(i -> workItem(i + 1)).toList();
    }

    /** 제출된 runnable을 붙잡아 둔다. 실행 시점을 테스트가 통제해야 "worker가 끝나야 슬롯이 돌아온다"를 볼 수 있다. */
    private List<Runnable> captureSubmissions() {
        List<Runnable> submitted = new ArrayList<>();
        org.mockito.BDDMockito.willAnswer(invocation -> {
            submitted.add(invocation.getArgument(0));
            return null;
        }).given(batchExecutor).execute(any(Runnable.class));
        return submitted;
    }

    @Test
    @DisplayName("실행 슬롯 수만큼만 선점한다 — 큐 길이로 더 가져가지 않는다")
    void 슬롯_수만큼만_선점한다() {
        captureSubmissions();
        given(dispatchStateService.claimDueBatch(CAPACITY)).willReturn(workItems(CAPACITY));

        scheduler.retryScheduler();

        // batch-size는 100이지만 실제 요청은 확보한 슬롯 수다
        verify(dispatchStateService).claimDueBatch(CAPACITY);
        assertThat(slots.available()).as("3건이 worker로 넘어갔으므로 남은 슬롯은 0").isZero();

        scheduler.retryScheduler();

        // 슬롯이 없으면 DB를 조회조차 하지 않는다. 실행 못 할 행을 PROCESSING으로 만들지 않는다
        verify(dispatchStateService, times(1)).claimDueBatch(anyInt());
    }

    @Test
    @DisplayName("worker가 끝나야 슬롯이 돌아온다 (성공·예외 모두)")
    void worker_종료가_슬롯을_반환한다() {
        List<Runnable> submitted = captureSubmissions();
        given(dispatchStateService.claimDueBatch(CAPACITY)).willReturn(workItems(CAPACITY));
        scheduler.retryScheduler();
        assertThat(slots.available()).isZero();

        // 한 건은 정상 종료, 한 건은 dispatch가 예외를 던지는 상황
        org.mockito.BDDMockito.willThrow(new RuntimeException("boom"))
                .given(dispatchService).dispatchClaimed(any(ClaimedNotification.class));
        submitted.forEach(Runnable::run);

        assertThat(slots.available()).as("예외로 끝난 worker도 슬롯을 반환해야 한다").isEqualTo(CAPACITY);
    }

    @Test
    @DisplayName("due가 요청보다 적으면 남는 슬롯을 즉시 돌려준다")
    void 선택된_행이_적으면_남는_슬롯을_즉시_반환한다() {
        captureSubmissions();
        given(dispatchStateService.claimDueBatch(CAPACITY)).willReturn(workItems(1));

        scheduler.retryScheduler();

        assertThat(slots.available()).as("3개 확보 → 1건만 선점 → 2개 즉시 반환").isEqualTo(CAPACITY - 1);
    }

    @Test
    @DisplayName("due가 하나도 없으면 확보한 슬롯을 전부 돌려준다")
    void 빈_결과는_슬롯을_전부_반환한다() {
        given(dispatchStateService.claimDueBatch(CAPACITY)).willReturn(List.of());

        scheduler.retryScheduler();

        assertThat(slots.available()).isEqualTo(CAPACITY);
        verify(batchExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("claim DB 실패는 슬롯을 전부 돌려주고 다음 scan으로 넘긴다")
    void claim_DB_실패는_슬롯을_누수시키지_않는다() {
        given(dispatchStateService.claimDueBatch(CAPACITY))
                .willThrow(new org.springframework.dao.QueryTimeoutException("lock wait timeout"));

        scheduler.retryScheduler();

        assertThat(slots.available()).isEqualTo(CAPACITY);
    }

    @Test
    @DisplayName("제출이 거절되면 슬롯을 돌려주고 그 건을 원래 대기 상태로 되돌린다")
    void 제출_거절은_슬롯과_상태를_함께_되돌린다() {
        org.mockito.BDDMockito.willThrow(new TaskRejectedException("pool full"))
                .given(batchExecutor).execute(any(Runnable.class));
        given(dispatchStateService.claimDueBatch(CAPACITY)).willReturn(workItems(CAPACITY));

        scheduler.retryScheduler();

        assertThat(slots.available()).isEqualTo(CAPACITY);
        verify(dispatchStateService, times(CAPACITY)).releaseBatchClaim(any(ClaimedNotification.class));
    }

    @Test
    @DisplayName("한 건의 반환 실패가 나머지 건의 정리를 막지 않는다")
    void 반환_실패가_뒤의_작업을_방치하지_않는다() {
        org.mockito.BDDMockito.willThrow(new TaskRejectedException("pool full"))
                .given(batchExecutor).execute(any(Runnable.class));
        given(dispatchStateService.claimDueBatch(CAPACITY)).willReturn(workItems(CAPACITY));
        // 첫 건의 보상 TX만 DB 오류로 실패한다
        org.mockito.BDDMockito.willThrow(new org.springframework.dao.QueryTimeoutException("release failed"))
                .willDoNothing()
                .given(dispatchStateService).releaseBatchClaim(any(ClaimedNotification.class));

        scheduler.retryScheduler();

        // 반환하지 못한 행은 PROCESSING으로 남아 lease 회수가 이어받는다. 슬롯 회계는 그와 별개로 정확해야 한다
        assertThat(slots.available()).isEqualTo(CAPACITY);
        verify(dispatchStateService, times(CAPACITY)).releaseBatchClaim(any(ClaimedNotification.class));
    }
}
