# 알림 발송 시스템 (BE-C)

> 수강 신청 완료, 결제 확정 등 비즈니스 이벤트 발생 시 이메일 또는 인앱 알림을 발송하는 백엔드 시스템입니다.

---

## 목차

1. [프로젝트 구조](#1-프로젝트-구조)
2. [요구사항 해석 및 개선 의견](#2-요구사항-해석-및-개선-의견)
3. [ERD](#3-erd)
4. [비동기 처리 구조 및 재시도 정책](#4-비동기-처리-구조-및-재시도-정책)
5. [테스트 코드](#5-테스트-코드)
6. [AI 활용 범위](#6-ai-활용-범위)

---

## 1. 프로젝트 구조

### 헥사고날 아키텍처란?

헥사고날 아키텍처(Hexagonal Architecture)는 **핵심 비즈니스 로직을 외부 기술(DB, HTTP, 메시지 브로커 등)로부터 분리**하는 구조입니다.

"알림을 발송한다"는 핵심 로직이 "이메일로 보내느냐, DB에 MySQL을 쓰느냐"를 알 필요가 없도록 설계합니다. 핵심 로직은 인터페이스(Port)만 바라보고, 실제 구현은 어댑터(Adapter)가 담당합니다.

```
┌─────────────────────────────────────────────────────────────┐
│                     Adapters (IN)                           │
│           NotificationController (HTTP API)                 │
└─────────────────────────┬───────────────────────────────────┘
                           │
┌─────────────────────────▼───────────────────────────────────┐
│                  Application (Core)                         │
│  RegisterNotificationUseCase  ←→  NotificationRepositoryPort│
│  GetNotificationUseCase       ←→  ChannelSenderPort         │
│  NotificationService / DispatchService                      │
└─────────────────────────┬───────────────────────────────────┘
                           │
┌─────────────────────────▼───────────────────────────────────┐
│                    Adapters (OUT)                           │
│    NotificationRepositoryImpl (JPA)                         │
│    LogChannelSenderAdapter (이메일/인앱 Mock 발송)            │
└─────────────────────────────────────────────────────────────┘
```

### 실제 패키지 구조

```
src/main/java/com/notification/
├── adapter/
│   └── in/web/              # HTTP 어댑터 (컨트롤러, DTO, 검증)
├── application/
│   ├── port/
│   │   ├── in/              # UseCase 인터페이스 (입력 포트)
│   │   └── out/             # Repository/Sender 인터페이스 (출력 포트)
│   └── service/             # 핵심 비즈니스 로직
├── domain/                  # 도메인 엔티티 (Notification, DispatchHistory 등)
└── infrastructure/
    ├── adapter/out/channel/ # 채널 발송 어댑터 (현재: 로그 출력)
    ├── repository/          # JPA 구현체
    └── config/              # Spring 설정 (Swagger, AsyncConfig 등)
```

### 헥사고날 아키텍처 장단점

**장점**

- **교체가 쉽습니다**: 현재 이메일 발송은 로그 출력(`LogChannelSenderAdapter`)으로 Mock 처리되어 있습니다. 실제 SendGrid API를 연동하려면 `ChannelSenderPort`를 구현한 새 어댑터만 만들면 되고, 핵심 로직 코드는 바꾸지 않아도 됩니다.
- **테스트하기 쉽습니다**: DB 없이 단위 테스트를 작성할 때 인터페이스(Port)만 Mock으로 대체하면 됩니다.
- **관심사가 명확히 분리됩니다**: "발송 실패 시 재시도한다"는 비즈니스 규칙이 JPA나 HTTP 코드와 섞이지 않습니다.

**단점**

- **초기 코드량이 많습니다**: Port 인터페이스, Command/Result 객체, 구현체까지 모두 만들어야 해서 간단한 기능도 파일이 여러 개 생깁니다.
- **익숙하지 않으면 구조 파악이 어렵습니다**: 처음 보는 사람은 초기 학습 비용이 들 것이라 생각합니다.

---

## 2. 요구사항 해석 및 개선 의견

### 핵심 요구사항 해석

**"알림 처리 실패가 비즈니스 트랜잭션에 영향을 주어서는 안 된다"**

결제 서버와 알림 서버가 분리된 구조에서 결제 서버가 알림 서버에 이벤트를 전달하다가 서버 종료나 네트워크 단절이 발생하면 메시지가 유실되고, 알림 발송 기록도 없고 재시도도 되지 않습니다. 이 문제를 해결하기 위해 **Transactional Outbox Pattern**을 적용했습니다.

> 참고: [트랜잭셔널 아웃박스 패턴의 실제 구현 사례 - 29CM](https://medium.com/@greg.shiny82/%ED%8A%B8%EB%9E%9C%EC%9E%AD%EC%85%94%EB%84%90-%EC%95%84%EC%9B%83%EB%B0%95%EC%8A%A4-%ED%8C%A8%ED%84%B4%EC%9D%98-%EC%8B%A4%EC%A0%9C-%EA%B5%AC%ED%98%84-%EC%82%AC%EB%A1%80-29cm-0f822fc23edb)


---

### Transactional Outbox Pattern

**실제 환경의 문제 상황**

실제 서비스라면 결제 완료 후 Kafka 같은 메시지 브로커에 이벤트를 발행하고, 별도 컨슈머가 알림을 발송합니다. 그런데 "DB에 결제 저장"과 "Kafka에 이벤트 발행"은 하나의 트랜잭션으로 묶을 수 없어서 아래 문제가 생깁니다.

```
1. DB에 결제 저장 성공
2. Kafka에 이벤트 발행 실패 → 알림 못 보냄 (사용자는 모름)

또는

1. DB에 결제 저장 실패
2. Kafka에 이벤트 발행 성공 → 결제는 안 됐는데 알림이 감
```

**Outbox Pattern으로 해결**

이벤트 발행 대신 **DB에 "발송 예정 기록(PENDING)"을 저장**하고, 이후 별도 프로세스가 이를 읽어서 발송합니다. DB 저장과 이벤트 발행이 같은 트랜잭션 안에 있으므로 둘 다 성공하거나 둘 다 실패합니다.

**이 과제에서의 적용 코드**

```java
// NotificationService.register() — 트랜잭션 안에서 저장 + 이벤트 발행
@Transactional
public RegisterNotificationResult register(RegisterNotificationCommand command) {
    // 1. DB에 PENDING 상태로 저장
    Notification saved = notificationRepositoryPort.save(notification);

    // 2. 트랜잭션 커밋 후에만 이벤트 발행
    //    커밋 전 서버 재시작 → 알림 레코드도 롤백 → 데이터 유실 없음
    eventPublisherPort.publish(new NotificationCreatedEvent(saved.getId(), saved.getScheduledAt()));
    return RegisterNotificationResult.from(saved);
}
```

```java
// NotificationEventHandler — 커밋 완료 후 별도 스레드에서 발송
@Async("notificationExecutor")
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handle(NotificationCreatedEvent event) {
    // scheduledAt이 미래 시각이면 즉시 발송 생략 → 스케줄러가 예정 시각에 처리
    if (event.scheduledAt() != null && event.scheduledAt().isAfter(LocalDateTime.now())) {
        return;
    }
    dispatchService.dispatch(event.notificationId());
}
```

커밋 후 발송에 실패하더라도 DB에 PENDING 상태로 남아 있으므로 스케줄러가 1분마다 재처리합니다.

> **구현 범위 참고**: 이 과제에서 `eventPublisherPort`는 Spring의 `ApplicationEventPublisher`를 감싼 JVM 내부 이벤트입니다. Kafka 같은 외부 브로커를 쓰는 완전한 Outbox Pattern과는 차이가 있습니다. Kafka 전환 시에는 DB 저장과 브로커 발행의 원자성이 다시 깨지므로, 그 시점에 Debezium(CDC) 방식으로 전환하는 것이 맞다고 생각합니다. 다만 이 과제 범위에서는 `NotificationEventPublisherPort` 인터페이스로 분리해 두었기 때문에 핵심 로직 변경 없이 구현체 교체만으로 전환 가능하도록 설계했습니다.

실제 Kafka 환경으로 전환하려면 `NotificationEventPublisherPort` 구현체만 교체하면 되고, 핵심 로직은 변경하지 않아도 됩니다.

**스케줄러가 필요한 이유**

`@TransactionalEventListener(AFTER_COMMIT)` + `@Async`로 커밋 직후 발송을 시도하지만, 이 경로는 "최선 노력(best-effort)"입니다. 이벤트 핸들러 실행 중 서버가 비정상 종료되거나 스레드 풀이 포화 상태이면 발송이 누락됩니다. 이 경우 PENDING 상태로 남은 레코드를 스케줄러가 주기적으로 폴링해 재처리합니다.

| 구분 | 역할 |
|------|------|
| 이벤트 핸들러 | 등록 직후 빠른 발송 (정상 경로) |
| 스케줄러 (1분) | 누락·실패 건 재처리 (안전망) |
| ShedLock | 다중 인스턴스 환경에서 스케줄러 중복 실행 방지 |

**재시도 정책 근거**

재시도 횟수를 3회, 간격을 1분 → 5분으로 설정한 이유는 외부 채널(이메일, 인앱) 장애가 일시적인 경우(네트워크 순단, 외부 API 과부하)라면 수 분 내 복구되는 경우가 대부분이고, 무한 재시도는 정말로 복구 불가능한 오류(잘못된 수신자 주소 등)에서 리소스를 낭비하기 때문입니다. 3회 초과 시 FAILED로 확정하고 운영자가 판단 후 수동 재시도하는 정책이 현실적이라고 판단했습니다.

---

### 멱등성 키 정책

**동일 (이벤트, 수신자, 채널) 조합당 1건만 발송**으로 정의합니다.

멱등성 키 생성 재료: `notificationType | eventId | receiverId | channel`

```java
// SHA-256 해시로 변환해 DB unique 인덱스에 저장
String raw = type + "|" + eventId + "|" + receiverId + "|" + channel;
```

- 같은 결제 이벤트에 EMAIL과 IN_APP을 각각 요청하면 **채널이 다르므로 다른 키** → 두 채널 모두 발송됩니다. 이는 의도된 동작입니다.
- `channelTarget`(이메일 주소)이나 `contentData`(내용)가 달라도 위 4가지가 같으면 **동일 이벤트로 처리**되어 첫 번째 요청 데이터로 발송됩니다. 이메일 주소나 내용을 바꾸고 싶다면 새 `eventId`를 사용해야 합니다.

> **호출자 계약**: `eventId`는 호출자(결제 서버 등)가 비즈니스 이벤트 단위로 고유하게 생성해서 넘겨야 합니다. 같은 이벤트에 `eventId`를 다르게 설정하면 중복 발송이 발생하고, 이는 이 시스템이 막을 수 없는 영역입니다. Swagger 요청 예시에 이 계약을 명시해 두었습니다.

---

### 외부 채널 At-Least-Once 한계와 개선 방향

#### 한계

`send()`는 DB 트랜잭션 바깥에서 실행됩니다. "외부 이메일 API 호출은 성공했지만 직후 서버가 죽어서 DB 커밋이 안 된" 경우, 재시도 시 메일이 두 번 발송될 수 있습니다. 이 시스템은 **At-Least-Once** 보장이며, Exactly-Once는 보장하지 않습니다.

현재 `LogChannelSenderAdapter`는 로그 출력만 하므로 부수효과가 없어 이 문제가 실제로 발생하지 않습니다.

#### 개선 방향

**1. 외부 API MessageId 기반 중복 제거**

SendGrid는 `batch_id`, AWS SES는 발신 시 반환되는 `MessageId`를 `dispatch_history`에 저장해두면, 재시도 전에 해당 ID로 발송 여부를 조회해 중복을 방지할 수 있습니다. 단, API마다 지원 방식이 다르므로 채널 어댑터별로 구현해야 합니다.

**2. CDC(Change Data Capture)로 폴링 스케줄러 대체**

현재는 1분마다 DB를 폴링하는 스케줄러로 PENDING 레코드를 처리합니다. Debezium 같은 CDC 도구를 도입하면 DB 트랜잭션 로그(binlog)를 실시간으로 읽어 Kafka로 스트리밍할 수 있습니다. 폴링 없이 레코드가 INSERT되는 즉시 컨슈머가 처리하므로 발송 지연이 1분에서 수백 밀리초 수준으로 줄어듭니다. 다만 Debezium + Kafka 인프라가 추가로 필요합니다.

---

### Swagger API 확인 방법

```bash
# 1. .env 파일 준비 (최초 1회)
cp .env.example .env
# .env 파일에 DB 정보 입력 후

# 2. Docker로 실행
docker-compose up --build -d

# 3. 브라우저에서 접속
# http://localhost:8080/swagger-ui.html
```

모든 API는 **`X-User-Id` 헤더**가 필수입니다. Swagger UI 상단의 X-User-Id 입력란에 숫자를 넣고 사용하면 됩니다.

> **주의**: `receiverId`와 `X-User-Id`는 같은 값을 사용해야 합니다. 결제 완료, 수강 신청 확정 같은 비즈니스 이벤트는 해당 행위를 한 사용자 본인에게 알림이 전달되는 구조로 생각했기 때문에, 알림을 요청하는 주체(X-User-Id)와 수신자(receiverId)가 동일합니다. 등록한 알림을 조회할 때도 같은 `X-User-Id`를 사용합니다.

---

## 3. ERD

> 아래는 실제 테이블 구조입니다. ERD 이미지는 별도 첨부를 참고합니다.

### notification (알림 원장)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | 알림 ID |
| receiver_id | BIGINT | 수신자 ID |
| notification_type | VARCHAR | 알림 유형 (PAYMENT_CONFIRMED 등) |
| channel | VARCHAR | 발송 채널 (EMAIL / IN_APP) |
| channel_target | VARCHAR | 이메일 주소 (IN_APP이면 NULL) |
| status | VARCHAR | 현재 상태 |
| idempotency_key | VARCHAR UNIQUE | 중복 방지 키 (SHA-256) |
| retry_count | INT | 재시도 횟수 (최대 3) |
| next_retry_at | DATETIME | 다음 재시도 예정 시각 |
| failure_reason | VARCHAR | 최종 실패 사유 코드 |
| is_read | BOOLEAN | 읽음 여부 (IN_APP 전용) |
| scheduled_at | DATETIME | 예약 발송 시각 (NULL이면 즉시) |
| created_at | DATETIME | 생성 시각 |
| updated_at | DATETIME | 마지막 수정 시각 |

### notification_log (상태 변경 이력)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | 로그 ID |
| notification_id | BIGINT FK | 알림 ID |
| from_status | VARCHAR | 이전 상태 |
| to_status | VARCHAR | 이후 상태 |
| reason | VARCHAR | 변경 사유 |
| created_at | DATETIME | 기록 시각 |

### dispatch_history (발송 시도 이력)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | 이력 ID |
| notification_id | BIGINT FK | 알림 ID |
| attempt_number | INT | 시도 회차 |
| status | VARCHAR | 성공/실패 |
| failure_code | VARCHAR | 실패 코드 (성공이면 NULL) |
| dispatched_at | DATETIME | 시도 시각 |

### shedlock (분산 스케줄러 락)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| name | VARCHAR PK | 락 이름 |
| lock_until | TIMESTAMP | 락 만료 시각 |
| locked_at | TIMESTAMP | 락 획득 시각 |
| locked_by | VARCHAR | 락 보유 인스턴스 |

### 알림 상태 전이

```
PENDING
  │
  ▼ 이벤트 핸들러 또는 스케줄러가 픽업
PROCESSING
  │
  ├─ 발송 성공 ──────────────────────────→ SENT
  │
  ├─ 일시 장애 (RetryableException), 재시도 횟수 < 3 → RETRYING
  │
  ├─ 일시 장애, 재시도 횟수 = 3 ─────────→ FAILED
  │
  └─ 영구 장애 (NonRetryableException) ──→ FAILED
```

---

## 4. 비동기 처리 구조 및 재시도 정책

### 전체 코드 흐름

```
클라이언트  POST /api/v1/notifications
                │
                ▼
    NotificationController.register()
                │
                ▼
    NotificationService.register()
                │
                ├─ 1. 멱등성 키 조회 → 이미 있으면 기존 결과 반환
                │
                ├─ 2. Notification saveAndFlush (status=PENDING)
                │      └─ 동시 중복 시 DataIntegrityViolationException
                │         → 기존 레코드 조회 후 반환 (200 OK)
                │         ※ save() 대신 saveAndFlush() 사용 — Hibernate는
                │           save() 후 flush를 커밋 시점까지 지연하므로
                │           catch 블록 범위 밖에서 예외가 터질 수 있음
                │
                ├─ 3. NotificationLog 기록 (CREATED)
                │
                └─ 4. NotificationCreatedEvent 발행
                            │
                            │ [트랜잭션 커밋 완료 후]
                            │ @TransactionalEventListener(AFTER_COMMIT)
                            │ @Async("notificationExecutor") — 별도 스레드
                            ▼
                NotificationEventHandler.handle()
                            │
                            ├─ scheduledAt이 미래 → 생략, 스케줄러 위임
                            │
                            ▼
                NotificationDispatchService.dispatch()
                            │
                            ├─ 조건부 UPDATE tryStartProcessing()
                            │   WHERE status IN ('PENDING','RETRYING')
                            │   → PROCESSING (원자적, 1개 스레드만 성공)
                            │   다른 스레드가 선점했으면 → 스킵
                            │
                            ├─ channelSenderPort.send()
                            │
                            ├─ 성공 → SENT
                            ├─ RetryableException → RETRYING + 지수 백오프
                            └─ NonRetryableException → FAILED

[1분마다] retryScheduler
    └─ PENDING/RETRYING 중
       scheduledAt IS NULL OR scheduledAt <= now   ← 예약 시각 도래 확인
       nextRetryAt IS NULL OR nextRetryAt <= now   ← 재시도 대기 완료 확인
       두 조건 모두 만족하는 행만 SKIP LOCKED로 조회 → dispatch() 재처리

[5분마다] stuckRecoveryScheduler
    └─ 10분 이상 PROCESSING인 알림 조회
       → 조건부 UPDATE (status='PROCESSING' AND updated_at <= threshold)
       → PENDING 복구 (retry_count 유지) → 다음 스케줄러 사이클에서 재처리
       ※ retry_count를 초기화하지 않는 이유: stuck은 발송 시도 자체가
         완료되지 않은 상태이므로 횟수를 그대로 유지하는 것이 맞다고 판단함.
         운영자가 판단해 수동으로 초기화하는 경우는 FAILED 이후 어드민 API로 처리
```

### 재시도 정책 (지수 백오프)

| 재시도 회차 | 다음 시도까지 대기 |
|------------|-----------------|
| 1회 실패 후 | 1분 후 |
| 2회 실패 후 | 5분 후 |
| 3회 실패 후 | **최종 FAILED** |

- **RetryableException**: 네트워크 오류, 서버 일시 장애 등 → 다음 사이클에 재시도합니다.
- **NonRetryableException**: 이메일 주소 없음 등 논리적 오류 → 즉시 FAILED 처리합니다.

### 다중 인스턴스 중복 방지

| 방어 단계 | 메커니즘 |
|-----------|---------|
| 조회 단계 | SKIP LOCKED — 다른 인스턴스가 잠금 획득한 행 건너뜀 |
| 처리 시작 | 조건부 UPDATE (`WHERE status IN ('PENDING','RETRYING')`) — 1개 인스턴스만 PROCESSING 전환 성공 |
| Stuck 복구 | 조건부 UPDATE (`WHERE status='PROCESSING' AND updated_at <= threshold`) — 이미 SENT된 행은 0행 업데이트로 안전 스킵 |
| 스케줄러 실행 | ShedLock — 다중 인스턴스 중 1개에서만 스케줄러 실행 |

> **ShedLock 한계**: 락을 잡은 인스턴스가 실행 도중 비정상 종료되면 `lock_until`이 만료될 때까지 다른 인스턴스가 스케줄러를 실행하지 못합니다. 이 때문에 `lock_until`을 스케줄 주기보다 살짝 길게(예: 1분 주기라면 70초) 설정해서 공백 시간을 최소화하는 것이 좋다고 생각합니다.

> **스레드 풀 포화 시**: `@Async` 실행기의 큐가 꽉 차면 Spring이 `TaskRejectedException`을 던집니다. 트랜잭션은 이미 커밋된 이후라 롤백은 안 되지만, PENDING 레코드가 DB에 남아 있으므로 1분 후 스케줄러가 정상 처리합니다. 다만 이 예외가 로그에 남지 않으면 운영 중 무음 실패가 될 수 있어, 실행기에 `RejectedExecutionHandler`를 등록해 로그를 남기는 것이 좋다고 생각합니다.

### 최종 실패(FAILED) 시 운영 정책

현재 FAILED 상태가 되면 스케줄러가 다시 픽업하지 않습니다. 운영 환경에서는 아래 정책을 권장합니다.

1. **모니터링 연동**: FAILED 알림 발생 시 운영팀 슬랙/이메일로 즉시 통보합니다.
2. **수동 재시도 API**: 운영자가 FAILED 알림을 선택해 PENDING으로 되돌리는 어드민 API를 제공합니다.
3. **재시도 횟수 초기화 기준**:
   - 외부 채널 장애 복구 후 재시도라면 → `retry_count` 초기화 O (장애가 해소됐으므로)
   - 동일 오류가 반복될 것으로 판단되면 → `retry_count` 초기화 X (불필요한 재시도 방지)

---

## 5. 테스트 코드

### 테스트 환경 준비

**Docker Desktop이 실행 중이어야 합니다.** 통합 테스트는 Testcontainers가 MySQL 8.0 컨테이너를 자동으로 띄웁니다.

```bash
# 전체 테스트 실행
./gradlew test

# 테스트 종류별 실행
./gradlew test --tests "com.notification.domain.*"           # 도메인 단위 테스트
./gradlew test --tests "com.notification.application.*"      # 서비스 단위 테스트
./gradlew test --tests "com.notification.resilience.*"       # 장애·복구 통합 테스트
./gradlew test --tests "com.notification.concurrency.*"      # 동시성 통합 테스트
```

### 로컬 서버 실행

```bash
# 1. 환경변수 파일 준비 (최초 1회)
cp .env.example .env
```

`.env.example`을 참고해 `.env` 파일에 DB 접속 정보를 채웁니다.

```bash
# 2. Docker로 앱 + MySQL 함께 실행
docker-compose up --build -d

# 3. Swagger UI 접속
# http://localhost:8080/swagger-ui.html
```

### 테스트 구성

#### 도메인 단위 테스트 (`NotificationTest`)

비즈니스 규칙을 검증합니다. DB나 Spring 없이 순수 Java 객체로 실행되어 가장 빠릅니다.

| 테스트 | 검증 내용 |
|--------|---------|
| 초기 상태 | 알림 생성 시 status=PENDING, retryCount=0 |
| 재시도 정책 | 1회 실패 → RETRYING + 1분 후 재시도, 3회 실패 → FAILED |
| 지수 백오프 | 1회: 1분, 2회: 5분 대기 확인 |
| NonRetryable | 즉시 FAILED, retryCount 증가 없음 |

#### 서비스 단위 테스트

Mock으로 DB 의존성을 제거하고 서비스 로직만 검증합니다.

| 테스트 파일 | 주요 검증 |
|------------|---------|
| `NotificationServiceTest` | 신규 등록 시 save/publish 1회, 중복 요청 시 기존 결과 반환 |
| `NotificationDispatchServiceTest` | 발송 성공/실패/재시도 각 경로의 상태 전이 및 이력 기록 |
| `GetNotificationServiceTest` | 본인 알림 조회 성공, 타인 알림 접근 시 404 |

#### 장애·복구 통합 테스트 (`ResilienceIntegrationTest`)

실제 MySQL 컨테이너를 사용하는 E2E 시나리오 테스트입니다.

| 시나리오 | 내용 |
|---------|------|
| **A. 서버 재시작** | PENDING으로만 저장하고 이벤트 발행 없이 → 스케줄러만으로 발송 완료 확인 |
| **B. Stuck 복구** | DB에서 직접 `updated_at`을 20분 전으로 변경 → `recoverStuck()` 호출 → PENDING 복구 → 재발송 → SENT |
| **C. 재시도** | 1회 일시 실패 → RETRYING → `next_retry_at` 과거로 조작 → 재발송 → SENT, DispatchHistory 2건 |
| **D. NonRetryable** | 영구 실패 채널 오류 → 즉시 FAILED, retryCount=0, 스케줄러 재처리 대상 아님 |
| **E. 최대 재시도** | 3회 연속 실패 → FAILED, DispatchHistory 3건, 이후 스케줄러 픽업 안 됨 |

> **테스트 전략**: ShedLock 최소 점유 시간(10초) 간섭을 피하기 위해 스케줄러 메서드를 직접 호출하지 않고 `dispatchService.fetchPendingIds()` + `dispatch()`를 직접 조합합니다. `@PreUpdate` 콜백이 `updated_at`을 자동 갱신하므로 시간 조작이 필요한 시나리오는 `JdbcTemplate`으로 DB를 직접 수정합니다.

#### 동시성 통합 테스트 (`ConcurrencyIntegrationTest`)

멀티 스레드 환경에서 중복 방지 메커니즘을 검증합니다.

| 테스트 | 시나리오 |
|--------|---------|
| **동시 중복 register** | 10개 스레드가 동시에 동일 키로 register → DB에 1건만 저장, 나머지는 기존 결과 반환 |
| **동시 dispatch** | 같은 알림을 여러 스레드가 동시에 dispatch → 1개만 PROCESSING 전환, 나머지 스킵 |
| **다중 인스턴스 스케줄러** | 5개 스레드가 동시에 fetchPendingIds → ID 중복 없음 |

---

## 6. AI 활용 범위

이 프로젝트는 AI(Claude)를 적극적으로 활용하되, **모든 코드와 설계를 직접 이해하고 검증**했습니다.

### 활용 내역

| 영역 | 활용 방식 |
|------|---------|
| **초기 설계 검토** | 헥사고날 구조, 상태 전이, 재시도 정책 설계 방향 질의 및 피드백 |
| **코드 구현** | 멱등성 키 생성 로직, CAS 쿼리, SKIP LOCKED 쿼리, 지수 백오프 로직 피드백 |
| **버그 발견 및 수정** | `scheduledAt` 미래 발송 즉시 트리거 버그, `recoverStuck` 비-CAS 경합 문제, `DataIntegrityViolationException` catch 위치 문제 등 엣지 케이스 분석 |
| **테스트 코드** | 시나리오별 통합 테스트 초안 작성 및 `@PreUpdate` 우회를 위한 JdbcTemplate 전략 제안 |
| **리팩토링** | `recordFailure()` 메서드 추출, 검증 로직(`@ValidChannelTarget`) 분리, 예외 핸들러 구조 개선 |
| **보안 개선** | 이메일 PII 마스킹, DB 자격 증명 하드코딩 제거, X-User-Id 헤더 유효성 검증 추가 |
| **문서화** | Javadoc, README, 커밋 메시지 작성 보조 |

### 검증 방식

- AI가 제안한 코드는 반드시 로컬에서 실행해 동작을 확인했습니다.
- 엣지 케이스 분석 결과는 실제 테스트 코드로 재현하거나 코드 리뷰를 통해 사실 여부를 검증했습니다.
- 설계 방향(멱등성 키 재료 선정, 재시도 횟수 3회 등)은 AI 제안을 참고하되 요구사항에 맞게 직접 판단했습니다.
- **AI가 제안했더라도 왜 그렇게 해야 하는지 설명하지 못하면 채택하지 않았습니다.**
