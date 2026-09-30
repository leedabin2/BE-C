# 알림 발송 시스템

수강 신청·결제 확정·강의 시작 등의 이벤트를 이메일 또는 인앱 알림으로 처리하는 Spring Boot 프로젝트입니다. 비동기 알림 과제에서 출발해, 여러 서버의 작업 분담과 실패 복구를 검증하는 방향으로 고도화했습니다. **실제 메일 발송과 메시지 브로커는 연결하지 않았으며**, 발송은 로그·테스트용 Mock으로 대체합니다.

**기간** 2026.05 (1차) · 2026.09 (고도화)

**기술** Java 17 · Spring Boot 3.5 · Spring Data JPA · MySQL 8 · ShedLock · Testcontainers · nGrinder

ShedLock은 Stuck 회수와 만료 정리처럼 **한 인스턴스만 돌면 되는 관리 스케줄러**에 씁니다. 발송 작업의 분담에는 쓰지 않습니다 — 그쪽은 아래에 적은 대로 DB가 정합니다.

## 해결하려는 문제

- 알림 발송이 느리거나 실패해도 요청 스레드가 외부 발송을 기다리지 않도록 합니다.
- 접수 후 프로세스가 종료되거나 워커 제출이 누락돼도 DB에 남은 작업을 다시 발견합니다.
- 여러 인스턴스가 동시에 실행될 때 동일 작업의 선점과 늦은 결과 반영을 제어합니다.
- 실패 원인과 알림의 유효 시간에 따라 재시도 여부를 결정합니다.

## 처리 흐름

```text
POST /api/v1/notifications
  → 멱등키 확인·UNIQUE 제약 → notification(PENDING) 커밋 → HTTP 200 (접수)
                                   │
                                   ├─ AFTER_COMMIT 이벤트 → 즉시 실행 시도
                                   └─ 1초 간격 due 스캔 → 누락·예약·재시도 작업 발견
                                                         (각 인스턴스에서 실행)
                                         │
                                         ▼
                짧은 TX-A: SKIP LOCKED 조회 + PROCESSING/token/lease 기록
                                         │
                        TX 밖: 발송 Mock 호출 (worker 스레드)
                                         │
                                         ▼
                짧은 TX-B: token 조건으로 SENT/RETRYING/FAILED 반영

       별도 30초 주기 Stuck 회수: 만료된 PROCESSING을 조건부 갱신
```

등록 시 DB에 저장된 `PENDING`이 복구 기준입니다. `AFTER_COMMIT` 이벤트는 빠른 발송 경로일 뿐, 이벤트나 executor 제출이 누락돼도 due 스캔이 다시 발견합니다. due 스캔은 모든 인스턴스에서 실행하며, 작업 소유권은 ShedLock이 아닌 DB의 `SKIP LOCKED` 조회와 조건부 갱신이 정합니다. ShedLock은 Stuck 회수·만료 정리 같은 관리 스케줄러에 사용합니다.

`lease_until`은 멈춘 워커의 작업을 다른 워커가 회수할 수 있게 하고, `processing_token`은 이전 워커의 늦은 DB 결과가 새 소유자의 상태를 덮지 못하게 합니다. 외부 발송 전에 token·남은 lease·알림 기한을 다시 확인합니다. 다만 **외부 업체가 실제로 발송한 직후 응답만 유실되면 재시도 시 중복 발송될 수 있습니다.** DB token만으로 외부 발송의 exactly-once는 보장하지 않습니다.

## 재시도와 제공자 보호

새 EMAIL 알림에는 타입별 발송 기한과 재시도 간격을 적용합니다. 예를 들어 결제 알림은 비교적 오래 재시도할 수 있지만, 강의 시작 안내는 생산자가 지정한 기한이 지나면 발송하지 않습니다. 기한이 이미 지났는지, 실패 횟수의 상한에 닿았는지, 다음 재시도 시각이 기한을 넘는지 순서대로 판정합니다. 워커 중단 후 lease 회수 횟수는 외부 발송 실패 횟수와 분리합니다. 이전에 저장된 알림은 `LEGACY_V0` 정책으로 읽어 기존 동작을 유지합니다.

일시적 실패는 다음 시각을 DB에 기록하고 worker를 반환합니다. 제공자의 `Retry-After`보다 일찍 다시 호출하지 않으며, 기본 지연에 **0~60초의 additive jitter**를 더합니다. 이는 AWS의 Full Jitter 공식과 다릅니다. 재시도 간격과 기한은 실제 이메일 업체의 SLA를 측정해 확정한 수치가 아니라 시험용 초기 정책입니다.

공유 DB의 `provider_circuit`은 제공자 범위의 연속 실패를 관찰해 새 호출을 잠시 보류하고, 복구 시 클러스터 전체에서 한 건만 탐침하도록 합니다. 반면 **N대 합산 발송 속도를 제한하는 전역 rate limiter는 아직 없습니다.** 차단 중에도 이미 시작된 외부 호출을 취소할 수 없고, 인증 실패를 직접 받은 알림은 현재 `FAILED`로 끝납니다.

## 검증한 범위

- MySQL Testcontainers로 동시 등록, 원자적 선점, lease 회수, 늦은 결과 차단, 기한·재시도 정책을 검증했습니다.
- 별도 JVM 3개가 같은 DB의 30개 작업을 분담하고, 발송 전 한 JVM을 종료한 뒤 만료 lease를 다른 JVM이 회수하는 테스트를 작성했습니다. 이 테스트의 발송은 실제 SMTP가 아닌 DB probe입니다.
- 로컬 Mock 부하 실험에서 10,000건 요청 중 9,010개 고유 알림이 저장됐고, 해당 알림의 Mock 발송 호출도 9,010회로 관측했습니다. **이는 그 실험 조건의 결과이지 실제 업체 발송 무중복 보장이 아닙니다.**
- 실행 대기열을 없앤 후보는 10,000건 처리가 5분을 넘었습니다. batch worker 15개와 대기열 15칸, 스캔 실행 중 빈 슬롯 재충전으로 조정한 뒤 같은 테스트를 통과했습니다. 수치는 로컬 환경과 Mock 조건에 한정됩니다.

테스트 실행에는 Docker가 필요합니다.

```bash
./gradlew test
```

부하 스크립트와 실행 방법은 [loadtest/README.md](loadtest/README.md)에 있습니다. 코드에서 [재처리 스케줄러](src/main/java/com/notification/infrastructure/scheduler/NotificationScheduler.java), [선점·회수 상태 관리](src/main/java/com/notification/application/service/DispatchStateService.java), [재시도 판정](src/main/java/com/notification/application/service/RetryDecisionPolicy.java), [다중 JVM 테스트](src/test/java/com/notification/multiprocess/MultiProcessClusterIntegrationTest.java)를 볼 수 있습니다.

## 로컬 실행

`.env.example`을 복사해 `.env`를 만들고 DB 값을 설정한 뒤 실행합니다. `.env`는 Git에 포함되지 않습니다.

```bash
cp .env.example .env
docker compose up --build -d
```

Swagger UI: `http://localhost:8080/swagger-ui.html`. 등록 API는 즉시 발송 완료가 아니라 **접수 결과를 HTTP 200으로 반환**합니다. `X-User-Id` 헤더는 과제용 사용자 식별 방식이며, 실제 인증·인가 체계가 아닙니다.

## 현재 한계와 다음 검증

결제 서버의 커밋과 이 시스템의 알림 접수 사이 유실은 여기서 해결하지 못합니다. 생산자 측 영속 전달(예: Outbox)이 필요합니다. `NotificationMessageHandler`는 수신 경계의 예시이며 Kafka 소비·ACK·재전달을 구현한 것은 아닙니다. 실제 업체를 연결할 때는 provider idempotency 계약이나 발송 결과 대사, 계정별 한도·오류 코드 매핑이 필요합니다.

다음 단계는 전역 발송량 제한, `eligible_at` 기반 due 조회 전환, 제공자 장애·회복과 N대 적체 소진 검증, 종료·롤링 배포 검증입니다. 현재 MySQL `ddl-auto: update`는 학습 환경용이며 운영 배포에는 버전 관리된 마이그레이션이 필요합니다.
