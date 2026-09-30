package com.notification.infrastructure.circuit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 제공자 차단 상태의 CAS 연산. 모든 UPDATE가 {@code generation}(또는 상태·시각)을 조건에 넣는다.
 * 읽고-판단하고-쓰는 사이에 다른 노드가 먼저 움직일 수 있기 때문이다.
 */
public interface ProviderCircuitJpaRepository extends JpaRepository<ProviderCircuit, String> {

    /** 없으면 CLOSED로 만든다. 동시 삽입은 PK 충돌로 한쪽만 이기고, 진 쪽은 조회로 수렴한다. */
    @Modifying
    @Query(value = "INSERT IGNORE INTO provider_circuit " +
                   "(scope, state, generation, consecutive_failures, backoff_step, updated_at) " +
                   "VALUES (:scope, 'CLOSED', 1, 0, 0, UTC_TIMESTAMP(6))",
           nativeQuery = true)
    int insertIfAbsent(@Param("scope") String scope);

    /**
     * OPEN 만료 뒤 <b>첫 한 명만</b> HALF_OPEN으로 전환하고 탐침 소유권을 가져간다.
     * generation을 조건에 넣었으므로 동시에 만료를 본 N대 중 한 노드만 1행을 얻는다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE provider_circuit SET state = 'HALF_OPEN', generation = generation + 1, " +
                   "probe_token = :probeToken, " +
                   "probe_until = TIMESTAMPADD(SECOND, :probeLeaseSeconds, UTC_TIMESTAMP(6)), " +
                   "updated_at = UTC_TIMESTAMP(6) " +
                   "WHERE scope = :scope AND generation = :generation " +
                   "AND state = 'OPEN' AND open_until IS NOT NULL AND open_until <= UTC_TIMESTAMP(6)",
           nativeQuery = true)
    int tryBeginProbeFromOpen(@Param("scope") String scope,
                              @Param("generation") long generation,
                              @Param("probeToken") String probeToken,
                              @Param("probeLeaseSeconds") int probeLeaseSeconds);

    /**
     * 탐침 소유자가 죽어 lease가 끝났으면 다른 노드가 인수한다.
     * 세대를 올려, 사라진 소유자의 늦은 결과가 새 탐침의 판정을 덮지 못하게 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE provider_circuit SET generation = generation + 1, probe_token = :probeToken, " +
                   "probe_until = TIMESTAMPADD(SECOND, :probeLeaseSeconds, UTC_TIMESTAMP(6)), " +
                   "updated_at = UTC_TIMESTAMP(6) " +
                   "WHERE scope = :scope AND generation = :generation " +
                   "AND state = 'HALF_OPEN' AND probe_until IS NOT NULL AND probe_until <= UTC_TIMESTAMP(6)",
           nativeQuery = true)
    int tryTakeOverExpiredProbe(@Param("scope") String scope,
                                @Param("generation") long generation,
                                @Param("probeToken") String probeToken,
                                @Param("probeLeaseSeconds") int probeLeaseSeconds);

    /** 성공 반영. 같은 세대일 때만 닫는다 — 옛 세대의 늦은 성공은 0행으로 무시된다. */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE provider_circuit SET state = 'CLOSED', consecutive_failures = 0, " +
                   "backoff_step = 0, open_until = NULL, probe_token = NULL, probe_until = NULL, " +
                   "updated_at = UTC_TIMESTAMP(6) " +
                   "WHERE scope = :scope AND generation = :generation",
           nativeQuery = true)
    int tryClose(@Param("scope") String scope, @Param("generation") long generation);

    /** 실패 누적. 아직 임계 전이라 열지는 않는다. */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE provider_circuit SET consecutive_failures = consecutive_failures + 1, " +
                   "updated_at = UTC_TIMESTAMP(6) " +
                   "WHERE scope = :scope AND generation = :generation AND state = 'CLOSED'",
           nativeQuery = true)
    int tryCountFailure(@Param("scope") String scope, @Param("generation") long generation);

    /**
     * 차단. 세대를 올려 진행 중이던 호출의 결과가 이 차단을 되돌리지 못하게 한다.
     * {@code GREATEST}로 이미 잡힌 차단 해제 시각을 앞당기지 않는다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE provider_circuit SET state = 'OPEN', generation = generation + 1, " +
                   "consecutive_failures = 0, backoff_step = LEAST(backoff_step + 1, :maxBackoffStep), " +
                   "open_until = GREATEST(COALESCE(open_until, UTC_TIMESTAMP(6)), " +
                   "TIMESTAMPADD(SECOND, :openSeconds, UTC_TIMESTAMP(6))), " +
                   "probe_token = NULL, probe_until = NULL, updated_at = UTC_TIMESTAMP(6) " +
                   "WHERE scope = :scope AND generation = :generation",
           nativeQuery = true)
    int tryOpen(@Param("scope") String scope,
                @Param("generation") long generation,
                @Param("openSeconds") int openSeconds,
                @Param("maxBackoffStep") int maxBackoffStep);
}
