# 부하 테스트

부하 생성기를 앱과 **다른 프로세스**로 분리한다. 같은 JVM에서 돌리면 측정이 왜곡된다. (→ `docs/DECISIONS.md` D-016)

## 준비

```bash
cp .env.example .env          # 값 채우기
docker-compose up --build -d
brew install k6               # https://k6.io/docs/get-started/installation/
```

## 실행

```bash
# 1) 점진 증가 — 어느 지점에서 꺾이는지
k6 run loadtest/notification-load.js

# 2) 순간 폭증 — 워커풀 거부 → 스케줄러 회수가 보인다
k6 run -e SCENARIO=spike loadtest/notification-load.js

# 3) 멱등성 — 100 VU가 같은 eventId를 동시에 (F3-2)
k6 run -e SCENARIO=idempotency loadtest/notification-load.js
```

## 결과 확인

k6는 **접수(HTTP)** 까지만 본다. **발송 결과는 DB에서 확인한다.**

```sql
-- 상태 분포
SELECT status, COUNT(*) FROM notification GROUP BY status;

-- 중복 발송 여부: 알림 1건당 성공 시도가 2회 이상이면 중복이다
SELECT notification_id, COUNT(*) c FROM dispatch_history
 WHERE status = 'SUCCESS' GROUP BY notification_id HAVING c > 1;

-- 실패 사유 분포 (F2-3)
SELECT failure_reason, COUNT(*) FROM notification
 WHERE status IN ('RETRYING','FAILED') GROUP BY failure_reason;

-- 늦은 결과 = Stuck 복구가 발송 중인 건을 되돌렸다는 신호 (D-014)
SELECT COUNT(*) FROM notification_log WHERE reason = 'LATE_RESULT_IGNORED';

-- 멱등성 시나리오: 1이어야 한다
SELECT COUNT(*) FROM notification WHERE event_id = 'k6-idem-fixed';
```

## 무엇을 보는가

| 지표 | 기대 | 어긋나면 |
|---|---|---|
| 접수 p99 | **SMTP 지연과 무관** (수십 ms) | 발송이 응답을 막고 있다 (F1-1 위반) |
| 5xx 비율 | 0에 수렴 | 커넥션 풀 또는 등록 TX 병목 |
| 503의 `Retry-After` | 항상 존재 | 호출자가 재시도 시점을 모른다 (D-009) |
| `dispatch_history` 중복 | 0건 | 조건부 UPDATE가 뚫렸다 (F3-1) |
| `LATE_RESULT_IGNORED` | 0건 | 발송이 Stuck 임계보다 오래 걸린다 |

## 앱 내부 지표

부하 중 커넥션·워커풀을 보려면 Actuator가 필요하다. **아직 미도입** (PLAN 단계 10).

```
/actuator/metrics/hikaricp.connections.active
/actuator/metrics/hikaricp.connections.pending
/actuator/metrics/executor.active
```
