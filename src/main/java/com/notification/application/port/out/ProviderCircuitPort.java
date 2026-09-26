package com.notification.application.port.out;

import com.notification.domain.ProviderCallPermit;
import com.notification.domain.ProviderCircuitState;

import java.time.Duration;
import java.util.Optional;

/**
 * 제공자 scope의 공유 차단 상태. (가이드 §15.4)
 *
 * <p>scope는 `provider + 계정/자격증명 + endpoint` 단위다. EMAIL 하나로 묶으면 장애 난 업체 때문에
 * 정상 업체까지 멈춘다. 상태는 DB 한 행이라 N대가 같은 값을 보고, 판정은 전부 CAS로 한다 —
 * 한 노드의 메모리 차단기로는 "클러스터 전체 탐침 1건"을 만들 수 없다.
 */
public interface ProviderCircuitPort {

    /**
     * 외부 호출 1건의 허가를 시도한다.
     *
     * @return 호출해도 되면 permit. 차단 중이면 empty
     */
    Optional<ProviderCallPermit> tryAcquire(String scope);

    /** 호출이 성공했다. 같은 세대면 회로를 닫는다. */
    void recordSuccess(ProviderCallPermit permit);

    /**
     * 호출이 실패했다.
     *
     * @param scopeFailure 이 scope 전체의 문제인가(연결 불가·인증·quota). 수신자 오류면 false —
     *                     주소 하나가 틀렸다고 업체 전체를 막으면 안 된다
     * @param retryAfter   제공자가 지시한 대기. 있으면 차단 시간의 하한으로 쓴다
     */
    void recordFailure(ProviderCallPermit permit, boolean scopeFailure, Optional<Duration> retryAfter);

    /** 관측용 현재 상태. 판정에 쓰지 않는다. */
    ProviderCircuitState currentState(String scope);
}
