package com.notification.domain;

/**
 * 제공자 오류가 아니라 <b>우리 정책이 내린 종료 사유</b>.
 *
 * <p>{@code ChannelFailureCode}(제공자가 무슨 오류를 줬나)와 섞지 않는다. 마지막 제공자 오류는
 * 시도 이력(dispatch_history)에 그대로 남고, {@code notification.failure_reason}에는 이 사유가 남는다.
 * "왜 그만뒀는가"와 "무엇이 실패했는가"는 운영 대응이 다르기 때문이다.
 */
public enum DispatchFailureReason {

    /** 기한이 이미 지났다. 지금 보내도 의미가 없다. */
    EXPIRED,

    /** 이번 cycle의 재시도 예산을 다 썼다. 기한은 아직 남아 있을 수 있다. */
    RETRY_EXHAUSTED,

    /** 아직 기한 전이지만 다음 재시도 시각이 기한 밖이다. 유효한 다음 기회가 없다. */
    RETRY_WINDOW_EXCEEDED,

    /** lease 회수 상한에 도달했다. 발송 실패를 관측한 것이 아니라 소유자가 반복해서 사라진 경우다. */
    RECOVERY_EXHAUSTED
}
