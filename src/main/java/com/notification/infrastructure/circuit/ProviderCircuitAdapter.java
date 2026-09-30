package com.notification.infrastructure.circuit;

import com.notification.application.port.out.ProviderCircuitPort;
import com.notification.domain.ProviderCallPermit;
import com.notification.domain.ProviderCircuitState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * MySQL 한 행으로 구현한 제공자 차단기. (가이드 §15.4)
 *
 * <p>노드 메모리가 아니라 DB에 두는 이유는 하나다 — <b>"클러스터 전체에서 탐침 1건"</b>은 공유 상태가
 * 없으면 만들 수 없다. 노드마다 메모리 차단기를 붙이면 N대가 각자 탐침을 보내 회복 중인 업체를
 * 다시 쓰러뜨린다.
 *
 * <p>모든 전이는 {@code generation}을 조건에 넣은 CAS다. 읽고-판단하고-쓰는 사이에 다른 노드가
 * 먼저 움직일 수 있고, 그때는 0행으로 조용히 지는 편이 맞다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderCircuitAdapter implements ProviderCircuitPort {

    private final ProviderCircuitJpaRepository repository;

    /** 이만큼 연속 실패하면 차단한다. 최근 N건 실패율이 아니라 연속 실패 기준이다. */
    @Value("${notification.provider.circuit.failure-threshold:5}")
    private int failureThreshold;

    /** 첫 차단 시간. 재오픈될수록 2배씩 늘린다. */
    @Value("${notification.provider.circuit.open-seconds:30}")
    private int baseOpenSeconds;

    @Value("${notification.provider.circuit.max-backoff-step:5}")
    private int maxBackoffStep;

    /** 탐침 소유권 유효 시간. 소유자가 죽으면 이 뒤에 다른 노드가 인수한다. */
    @Value("${notification.provider.circuit.probe-lease-seconds:60}")
    private int probeLeaseSeconds;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ProviderCallPermit> tryAcquire(String scope) {
        ProviderCircuit circuit = load(scope);

        return switch (circuit.getState()) {
            case CLOSED -> Optional.of(new ProviderCallPermit(scope, circuit.getGeneration(), null));
            case OPEN -> tryStartProbe(circuit);
            case HALF_OPEN -> tryTakeOverProbe(circuit);
        };
    }

    /** OPEN이 만료됐으면 첫 한 명만 탐침 소유권을 얻는다. 나머지는 계속 차단이다. */
    private Optional<ProviderCallPermit> tryStartProbe(ProviderCircuit circuit) {
        String probeToken = UUID.randomUUID().toString();
        if (repository.tryBeginProbeFromOpen(circuit.getScope(), circuit.getGeneration(),
                probeToken, probeLeaseSeconds) == 0) {
            return Optional.empty();   // 아직 차단 중이거나, 다른 노드가 먼저 탐침을 가져갔다
        }
        log.warn("[제공자 차단기] {} 탐침 시작. 클러스터 전체에서 이 호출 1건만 나간다.", circuit.getScope());
        return Optional.of(new ProviderCallPermit(circuit.getScope(), circuit.getGeneration() + 1, probeToken));
    }

    /** 탐침 소유자가 죽었으면 인수한다. 살아 있으면 차단 유지 — 동시 탐침을 만들지 않는다. */
    private Optional<ProviderCallPermit> tryTakeOverProbe(ProviderCircuit circuit) {
        String probeToken = UUID.randomUUID().toString();
        if (repository.tryTakeOverExpiredProbe(circuit.getScope(), circuit.getGeneration(),
                probeToken, probeLeaseSeconds) == 0) {
            return Optional.empty();
        }
        log.warn("[제공자 차단기] {} 탐침 소유자 만료로 인수.", circuit.getScope());
        return Optional.of(new ProviderCallPermit(circuit.getScope(), circuit.getGeneration() + 1, probeToken));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(ProviderCallPermit permit) {
        if (repository.tryClose(permit.scope(), permit.generation()) == 0) {
            // 이 호출이 나간 뒤 회로가 다시 열렸다. 늦은 성공이 새 차단을 풀면 안 된다.
            log.warn("[제공자 차단기] {} 옛 세대({})의 늦은 성공 무시.", permit.scope(), permit.generation());
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(ProviderCallPermit permit, boolean scopeFailure, Optional<Duration> retryAfter) {
        if (!scopeFailure) {
            return;   // 수신자 주소 오류로 업체 전체를 막지 않는다
        }
        ProviderCircuit circuit = load(permit.scope());
        if (circuit.getGeneration() != permit.generation()) {
            log.debug("[제공자 차단기] {} 옛 세대({})의 늦은 실패 무시.", permit.scope(), permit.generation());
            return;
        }

        // 탐침 실패는 한 번으로 곧바로 다시 닫는다. 아직 회복되지 않았다는 직접 증거다.
        boolean shouldOpen = permit.isProbe()
                || circuit.getConsecutiveFailures() + 1 >= failureThreshold;
        if (!shouldOpen) {
            repository.tryCountFailure(permit.scope(), permit.generation());
            return;
        }

        int openSeconds = Math.max(openSecondsFor(circuit.getBackoffStep()), retryAfterSeconds(retryAfter));
        if (repository.tryOpen(permit.scope(), permit.generation(), openSeconds, maxBackoffStep) > 0) {
            log.error("[제공자 차단기] {} 차단. {}초 동안 이 scope의 새 호출을 보류한다.", permit.scope(), openSeconds);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ProviderCircuitState currentState(String scope) {
        // 행이 없다 = 아직 한 번도 실패한 적 없다 = CLOSED. 관측하려고 행을 만들지 않는다.
        return repository.findById(scope)
                .map(ProviderCircuit::getState)
                .orElse(ProviderCircuitState.CLOSED);
    }

    /** 재오픈될수록 대기를 2배로 늘린다. 회복되지 않은 업체를 같은 간격으로 계속 두드리지 않는다. */
    private int openSecondsFor(int backoffStep) {
        return baseOpenSeconds * (1 << Math.min(backoffStep, maxBackoffStep));
    }

    private int retryAfterSeconds(Optional<Duration> retryAfter) {
        return retryAfter.map(Duration::toSeconds).map(Long::intValue).orElse(0);
    }

    private ProviderCircuit load(String scope) {
        return repository.findById(scope).orElseGet(() -> {
            repository.insertIfAbsent(scope);
            return repository.findById(scope)
                    .orElseThrow(() -> new IllegalStateException("차단기 행 생성 실패. scope=" + scope));
        });
    }
}
