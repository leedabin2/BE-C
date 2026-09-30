// k6 부하 스크립트 — 알림 발송 시스템
//
// 실행:
//   docker-compose up -d                 # 앱 + MySQL 기동
//   brew install k6                      # 또는 https://k6.io/docs/get-started/installation/
//   k6 run loadtest/notification-load.js
//
// 옵션:
//   k6 run -e BASE_URL=http://localhost:8080 -e SCENARIO=spike loadtest/notification-load.js
//
// 왜 k6인가: JUnit 부하 테스트는 부하 생성기가 앱과 같은 JVM에서 돌아 측정이 왜곡된다.
// k6는 별도 프로세스라 앱의 자원을 뺏지 않는다. → docs/DECISIONS.md D-016

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = __ENV.SCENARIO || 'ramp';

// 접수 응답 지연. 이 값이 SMTP 지연과 무관해야 비동기 분리가 성립한다 (F1-1)
const acceptLatency = new Trend('accept_latency', true);
const duplicateHit  = new Counter('duplicate_accepted');   // 멱등성으로 합쳐진 요청
const rejected      = new Rate('rejected_5xx');

const scenarios = {
  // 점진 증가 — 어느 지점에서 꺾이는지 본다
  ramp: {
    executor: 'ramping-vus',
    stages: [
      { duration: '30s', target: 50 },
      { duration: '1m',  target: 200 },
      { duration: '1m',  target: 500 },
      { duration: '30s', target: 0 },
    ],
  },
  // 순간 폭증 — 전부 다른 eventId 10,000건. 워커풀 거부와 스케줄러 회수가 보인다
  spike: {
    executor: 'shared-iterations',
    vus: 200, iterations: 10000, maxDuration: '5m',
  },
  // 멱등성 — 같은 eventId를 동시에 때린다 (F3-2)
  idempotency: {
    executor: 'per-vu-iterations',
    vus: 100, iterations: 10,
  },
};

export const options = {
  scenarios: { [SCENARIO]: scenarios[SCENARIO] },
  // p(99)는 기본 집계에 없다. 명시해야 요약에 나온다
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // ★ 핵심 단언: 접수 응답은 SMTP 지연과 무관해야 한다
    'accept_latency': ['p(99)<1000'],
    'http_req_failed': ['rate<0.01'],
    'rejected_5xx': ['rate<0.01'],
  },
};

export default function () {
  const isIdem = SCENARIO === 'idempotency';

  // ⚠️ 멱등성 키 = SHA-256(type | eventId | receiverId | channel)
  // eventId만 고정하고 receiverId를 VU마다 다르게 두면 키가 갈라져 VU 수만큼 저장된다.
  // 같은 이벤트라도 수신자가 다르면 별개 알림이기 때문 — 설계상 맞는 동작이다.
  // 중복을 검증하려면 네 재료를 전부 고정해야 한다.
  const eventId = isIdem ? 'k6-idem-fixed' : `k6-${__VU}-${__ITER}-${Date.now()}`;
  const receiverId = isIdem ? 777 : __VU;

  const payload = JSON.stringify({
    receiverId: receiverId,
    notificationType: 'PAYMENT_CONFIRMED',
    channel: 'EMAIL',
    channelTarget: `user${receiverId}@load.test`,
    eventId: eventId,
    referenceId: __ITER + 1,
    referenceType: 'PAYMENT',
    contentData: '{}',
  });

  const res = http.post(`${BASE_URL}/api/v1/notifications`, payload, {
    headers: { 'Content-Type': 'application/json', 'X-User-Id': String(receiverId) },
    tags: { name: 'register' },
  });

  acceptLatency.add(res.timings.duration);
  rejected.add(res.status >= 500);

  check(res, {
    '접수 성공(2xx)': (r) => r.status >= 200 && r.status < 300,
    // 503이면 Retry-After가 반드시 있어야 한다 — 호출자에게 재시도 시점을 알려주는 계약 (D-009)
    '503이면 Retry-After 존재': (r) => r.status !== 503 || !!r.headers['Retry-After'],
  });

  if (isIdem && res.status < 300) duplicateHit.add(1);
}

export function handleSummary(data) {
  const m = data.metrics;
  const p = (name, stat) => m[name] ? Math.round(m[name].values[stat]) : 0;

  return {
    stdout: `
╔═══════════════ k6 부하 결과 (${SCENARIO}) ═══════════════
║ 요청       : ${p('http_reqs', 'count')}건  ·  ${Math.round(m.http_reqs?.values.rate || 0)} RPS
╠═════════════════ 접수 응답 (F1-1) ═════════════════════
║ p50 ${p('accept_latency','med')}ms · p95 ${p('accept_latency','p(95)')}ms · p99 ${p('accept_latency','p(99)')}ms · max ${p('accept_latency','max')}ms
║ 실패율     : ${((m.http_req_failed?.values.rate || 0) * 100).toFixed(2)}%
║ 5xx        : ${((m.rejected_5xx?.values.rate || 0) * 100).toFixed(2)}%
╠════════════════════════════════════════════════════════
║ 판정: 접수 p99가 SMTP 지연보다 훨씬 작아야 비동기 분리 성립
║ 발송 완료 여부는 DB에서 확인:
║   SELECT status, COUNT(*) FROM notification GROUP BY status;
╚════════════════════════════════════════════════════════
`,
  };
}
