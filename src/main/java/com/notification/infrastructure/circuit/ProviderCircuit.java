package com.notification.infrastructure.circuit;

import com.notification.domain.ProviderCircuitState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 제공자 scope의 공유 차단 상태 한 행.
 *
 * <p>상태 전이는 전부 native CAS로 한다. 이 엔티티는 조회·초기 생성 용도이며,
 * dirty checking으로 상태를 바꾸지 않는다 — N대가 같은 행을 두고 경쟁하기 때문이다.
 */
@Entity
@Table(name = "provider_circuit")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProviderCircuit {

    /** `provider + 계정/자격증명 + endpoint` 식별자. 예: {@code EMAIL:default}. */
    @Id
    @Column(length = 100)
    private String scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProviderCircuitState state;

    /**
     * 재오픈·탐침 전환마다 증가한다. 결과 반영 CAS가 이 값을 조건에 넣어,
     * 그 사이 회로가 다시 열렸다면 옛 호출의 결과가 반영되지 않는다.
     */
    @Column(nullable = false)
    private long generation;

    /** 연속 실패 수. 임계에 닿으면 OPEN. 최근 N건 실패율(sliding window)이 아니다. */
    @Column(nullable = false)
    private int consecutiveFailures;

    /** 몇 번째 차단인가. 재오픈될수록 대기를 늘린다. */
    @Column(nullable = false)
    private int backoffStep;

    private LocalDateTime openUntil;

    /** HALF_OPEN 탐침 소유권. 이 토큰을 가진 호출 1건만 업체를 두드린다. */
    @Column(length = 36)
    private String probeToken;

    /** 탐침 lease. 소유자가 죽으면 이 시각 뒤에 다른 노드가 인수한다(영구 HALF_OPEN 금지). */
    private LocalDateTime probeUntil;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static ProviderCircuit closed(String scope, LocalDateTime now) {
        ProviderCircuit circuit = new ProviderCircuit();
        circuit.scope = scope;
        circuit.state = ProviderCircuitState.CLOSED;
        circuit.generation = 1L;
        circuit.consecutiveFailures = 0;
        circuit.backoffStep = 0;
        circuit.updatedAt = now;
        return circuit;
    }
}
