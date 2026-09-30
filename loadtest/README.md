# 부하 테스트

부하 생성기는 앱과 **다른 프로세스**로 분리한다. 같은 JVM에서 돌리면 측정이 왜곡된다. S5의 기본 도구는 nGrinder이며, 기존 k6는 비교/간단 실행용으로 보존한다. nGrinder의 Controller는 실행과 보고서를 관리하고 Agent가 HTTP 요청을 만든다. Agent는 이 서비스의 worker나 scheduler가 아니다. [nGrinder 공식 저장소](https://github.com/naver/ngrinder), [새 HTTP 클라이언트 공식 예제](https://github.com/naver/ngrinder/wiki/The-New-nGrinder-HTTP-Client)를 기준으로 작성했다. 저장소는 2025-09부터 archive 상태이므로 임의로 최신 버전이라고 가정하지 말고, 사용하는 Controller/Agent 버전을 결과에 기록한다.

## S5-0: nGrinder 기준선

1. Docker Compose로 대상 앱과 MySQL을 먼저 띄운다. nGrinder Agent는 앱과 다른 JVM/호스트에서 실행한다.
2. Controller의 `Script`에서 Groovy 스크립트를 새로 만들고 [NotificationRegistrationTest.groovy](ngrinder/NotificationRegistrationTest.groovy)를 붙여 넣는다.
3. 실행 속성에 `-Dnotification.base-url=http://<agent에서-보이는-앱>:8080`을 넣고, `-Dnotification.scenario=unique`로 시작한다. nGrinder UI의 Advanced `Parameter` 입력은 `key=value`를 내부적으로 단일 `-Dparam=key=value`로 전달하므로 `notification.scenario=scheduled`처럼 입력한다. 실행 속성 전달 UI가 없다면 스크립트 상단의 기본 URL만 **테스트 주소**로 바꾼다.
4. 첫 실행은 `process=1, thread=1, run count=60`으로 API 계약과 Agent→대상 네트워크를 확인한다. 이 스크립트에는 pacing이 없으므로 목표 RPS가 아니라 closed-loop 계약 확인이다.

| 순서 | nGrinder 속성 | 실행 설정 예시 | 무엇을 검증하는가 |
|---|---|---|---|
| 기준선 | `scenario=unique` | 1 process × 1 thread × 60회 | 요청/응답 계약, Agent 경로, 정상 접수 |
| 멱등성 | `scenario=idempotency` | 1 × 100 × 1회 동시 시작 | 같은 키가 알림 1행으로 수렴하는지 |
| 점증 | `scenario=unique` | 1 × 10 → 50 → 100 thread, 각 3분 | HTTP p95/p99, 실제 TPS, due backlog 증가 시작점 |
| 급증 | `scenario=unique` | 1 × 200 thread, 1분 | 5xx/503, worker 포화 뒤 스케줄러 회수, 적체 소진 |
| N대 비교 | `scenario=unique` | 같은 입력을 앱 1대/3대에 각각 | HTTP 지연뿐 아니라 backlog·소진 시간·DB 대기 비교 |
| 예약 N대 claim | 별도 `S5ScheduledClaimTest.groovy` | 1 process × 20 threads, 60초 실행 후 저장된 due 시각까지 대기 | 같은 `scheduledAt`에 도달한 일을 세 scheduler가 분담하고 성공 시도는 알림당 1회인지 |

이 스크립트에는 `@RunRate`도 rate-controller도 없다. 설정한 process × thread는 동시 사용자 수이며, 각 사용자는 응답을 받은 뒤 다음 요청을 보낸다. 느린 응답, Agent CPU, 네트워크에 따라 실제 TPS가 달라진다. **설정한 thread 수를 실제 RPS라고 쓰지 말고 report의 TPS를 기록한다.** 고정 RPS/open-loop 시험은 별도 rate-controller 또는 다른 load generator가 필요하다.

예약 폭증은 별도 실험이다. 검증한 전용 `S5ScheduledClaimTest`는 process 준비 시 UTC `now + 120초`를 한 번 만들고 모든 VUser가 같은 시각을 사용한다. 기존 범용 script의 `scenario=scheduled` 기본은 60초이므로 혼동하지 않는다. 짧은 10초 실행은 Agent 준비 중 끝날 수 있어 사용하지 않는다. 등록 종료 후 DB에 저장된 due 시각 뒤 상태·시도 이력·세 앱 로그를 대조한다. 여러 Agent process를 쓸 때는 하나의 절대 UTC `scheduledAt`을 전달하도록 확장해야 한다.

### 3대 scheduler 예약 claim 실험

```bash
# app(8080 공개) + worker-b/c(내부 worker)를 같은 MySQL에 기동한다.
docker compose -f docker-compose.yml -f docker-compose.cluster-loadtest.yml up -d app worker-b worker-c
```

캐시된 기존 script revision을 재사용하지 않도록 [S5ScheduledClaimTest.groovy](ngrinder/S5ScheduledClaimTest.groovy)를 별도 Controller script로 등록해 선택한다. `1 process × 20 threads`, 60초로 실행하고 due 시각 뒤에 DB를 대조한다. 이 전용 script는 모든 요청에 같은 `scheduledAt`과 `ngrinder-s5-n3-scheduled-` prefix를 넣는다. `notification_log`에 worker ID는 아직 저장하지 않으므로 **분배 비율**은 각 컨테이너의 발송 로그로 보고, **정확성**은 DB의 알림당 `SENT` 이력 1건으로 확인한다. 이 한계는 다음 observability 보강 항목이다.

```sql
-- 예약이 실제 저장됐는지와 최종 상태
SELECT status, COUNT(*) AS count, SUM(scheduled_at IS NULL) AS missing_scheduled_at
FROM notification
WHERE event_id LIKE 'ngrinder-s5-n3-scheduled-%'
GROUP BY status;

-- 정상 Mock 조건에서 성공 시도 이력이 알림당 정확히 1건이어야 한다.
SELECT n.id
FROM notification n
LEFT JOIN dispatch_history h
  ON h.notification_id = n.id AND h.status = 'SENT'
WHERE n.event_id LIKE 'ngrinder-s5-n3-scheduled-%'
GROUP BY n.id
HAVING COUNT(h.id) <> 1;
```

2026-09-25 로컬 실행에서는 app/worker-b/worker-c를 같은 MySQL에 두고 DB 12,649건 모두 `scheduled_at` 저장·`SENT`를 확인했다. 재검토에서 `dispatch_history.status='SENT'` 12,649건·알림당 정확히 1건·늦은 결과 이력 0도 확인했다. 첫→마지막 SENT log 간격은 약 56.3초, due→마지막은 약 56.9초다. 컨테이너 성공 로그는 각각 4,300 / 4,049 / 4,300이다. Controller 계측 성공 수(10,381)와 DB 접수 행 수 불일치 원인은 미확정이다. 이 실행은 **N=3 정상 Mock 처리 기능 증거**로 사용하고 HTTP 처리량/운영 용량 수치는 보류한다.

`notification_log.to_status='SENT'`에는 늦은 성공 시도(`LATE_RESULT_IGNORED`)도 기록될 수 있다. 상태 전이 확인은 reason을 구분하고, 시도 성공은 `dispatch_history.status='SENT'`로 별도 대조한다. finish 전 프로세스가 죽으면 시도 이력도 없을 수 있으므로 이 집계만으로 실제 제공자의 중복 수신을 증명하지 않는다.

## 준비

```bash
cp .env.example .env          # 값 채우기
docker-compose up --build -d
```

## 실행

```bash
# 기존 k6: nGrinder 결과와 비교하거나 간단한 로컬 확인에 사용
k6 run loadtest/notification-load.js

# 2) 순간 폭증 — 워커풀 거부 → 스케줄러 회수가 보인다
k6 run -e SCENARIO=spike loadtest/notification-load.js

# 3) 멱등성 — 100 VU가 같은 eventId를 동시에 (F3-2)
k6 run -e SCENARIO=idempotency loadtest/notification-load.js
```

## 결과 확인

nGrinder/k6는 **접수(HTTP)** 까지만 본다. **발송 결과는 DB에서 확인한다.** 각 실행 전에 `runId` 또는 `eventId` prefix를 기록하고, 이전 실행 행과 섞어 집계하지 않는다.

```sql
-- 상태 분포
SELECT status, COUNT(*) FROM notification GROUP BY status;

-- 성공 시도 중복 여부 (정상 Mock 조건). 실행별 eventId/runId 범위도 제한한다.
SELECT notification_id, COUNT(*) c FROM dispatch_history
 WHERE status = 'SENT' GROUP BY notification_id HAVING c > 1;

-- 실패 사유 분포 (F2-3)
SELECT failure_reason, COUNT(*) FROM notification
 WHERE status IN ('RETRYING','FAILED') GROUP BY failure_reason;

-- 늦은 결과 = Stuck 복구가 발송 중인 건을 되돌렸다는 신호 (D-014)
SELECT COUNT(*) FROM notification_log WHERE reason = 'LATE_RESULT_IGNORED';

-- 멱등성 시나리오: 1이어야 한다
SELECT COUNT(*) FROM notification WHERE event_id = 'k6-idem-fixed';
```

nGrinder의 `idempotency` 스크립트는 `ngrinder-idempotency-fixed`를 사용한다. unique 시나리오는 UUID eventId를 만들어 신규 등록 처리량을 재며, 멱등성 조회 처리량을 섞지 않는다.

## 무엇을 보는가

| 지표 | 기대 | 어긋나면 |
|---|---|---|
| 접수 p99 | SMTP 지연보다 충분히 작아야 함. 고정 수치는 기준선 뒤 확정 | 발송이 응답을 막거나 DB/CPU/로그 자원을 과점유 |
| 5xx 비율 | 0에 수렴 | 커넥션 풀 또는 등록 TX 병목 |
| 503의 `Retry-After` | 항상 존재 | 호출자가 재시도 시점을 모른다 (D-009) |
| `dispatch_history`의 SENT 중복 | 0건(정상 Mock 조건) | 정상 경합 오류 또는 결과 불명 재시도를 조사. DB token CAS가 정상이어도 외부 성공 중복은 가능 |
| `LATE_RESULT_IGNORED` | 0건 | 발송이 Stuck 임계보다 오래 걸린다 |

## 실행 기록에 반드시 남길 값

- git revision, 앱 대수, MySQL 사양/접속 풀, scheduler 주기·batch 크기·worker 수
- Controller/Agent 버전·Agent 수·process/thread/run count·실제 TPS·실행 시간
- HTTP 성공률/p95/p99와 `등록→SENT`, `due→발송 시작` 지연을 분리한 값
- 상태별 행 수, due backlog 최고치, backlog가 0이 되는 시간, 실패 코드/`LATE_RESULT_IGNORED` 수

HTTP가 빠르더라도 due backlog가 계속 증가하면 통과가 아니다. S5의 2초 지연 제공자 실험에서 `batch-size=100`과 기존 60초 polling이 due 후보를 분당 약 100건만 제출하는 병목을 보여, due dispatcher를 1초로 조정했다. 그래도 “1초마다 반드시 발송”을 보장하는 것은 아니다. scan 기회 뒤에도 큐·DB·외부 제공자 병목과 N대 합산 provider quota 때문에 더 늦어질 수 있으며, 이 실험은 그 한계를 수치로 찾는 과정이다.

## 앱 내부 지표

부하 중 커넥션·워커풀을 보려면 Actuator가 필요하다. **아직 미도입** (PLAN 단계 10).

```
/actuator/metrics/hikaricp.connections.active
/actuator/metrics/hikaricp.connections.pending
/actuator/metrics/executor.active
```
