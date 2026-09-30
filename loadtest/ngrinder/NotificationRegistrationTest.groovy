import static net.grinder.script.Grinder.grinder

import groovy.json.JsonOutput
import net.grinder.script.GTest
import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread
import org.junit.Test
import org.junit.runner.RunWith
import org.apache.hc.core5.http.Header
import org.apache.hc.core5.http.message.BasicHeader
import org.ngrinder.http.HTTPRequest
import org.ngrinder.http.HTTPResponse

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * nGrinder S5 — 알림 접수 API 부하 스크립트.
 *
 * Controller의 Script 메뉴에 이 파일 내용을 붙여 넣는다. Agent와 대상 앱은 반드시 다른 프로세스에 둔다.
 * - notification.base-url: Agent에서 보이는 앱 주소. 예: http://10.0.0.12:8080
 * - notification.scenario: unique(기본) | idempotency | scheduled
 *
 * nGrinder 실행 속성으로 위 두 값을 전달하지 못하는 환경이라면 아래 기본값을 테스트 환경의 주소로만 바꾼다.
 * 운영 URL·실제 수신자 주소·운영 이벤트 ID를 이 스크립트에 넣지 않는다.
 */
@RunWith(GrinderRunner)
class NotificationRegistrationTest {

    private static final String BASE_URL = System.getProperty(
            "notification.base-url", "http://host.docker.internal:8080")
    // nGrinder UI의 Advanced > Parameter는 `-Dparam=key=value`로 전달된다.
    // 로컬/CLI에서 직접 넘기는 -Dnotification.scenario도 계속 지원한다.
    private static final String PARAMETER = System.getProperty("param", "")
    private static final String SCENARIO = PARAMETER.startsWith("notification.scenario=")
            ? PARAMETER.substring("notification.scenario=".length())
            : System.getProperty("notification.scenario", "unique")
    /** 프로세스마다 분리되는 S5 식별자. 테스트 결과를 DB에서 안전하게 범위 조회할 때 쓴다. */
    private static final String RUN_ID = UUID.randomUUID().toString()
    /** scheduled 시나리오에서 모든 VUser가 같은 UTC 시각을 사용한다. */
    private static String scheduledAt

    private static GTest registerTest
    private static HTTPRequest request
    private static List<Header> headers

    @BeforeProcess
    static void beforeProcess() {
        registerTest = new GTest(1, "POST /api/v1/notifications")
        request = new HTTPRequest()
        headers = [
                new BasicHeader("Content-Type", "application/json"),
                new BasicHeader("X-User-Id", "1001")
        ]
        if (SCENARIO == "scheduled") {
            // 등록 부하는 짧게 끝낸 뒤 scheduler가 동시에 due 된 작업을 분담하게 한다.
            // Agent container와 DB 모두 UTC를 기준으로 하며, duration보다 충분히 미래여야 한다.
            // nGrinder는 script 준비에도 시간이 걸릴 수 있으므로 60초 후로 둔다.
            scheduledAt = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(60).toString()
        }
        grinder.logger.info("notification load test starts. baseUrl={}, scenario={}", BASE_URL, SCENARIO)
    }

    @BeforeThread
    void beforeThread() {
        // 두 번째 인자는 표시 이름이 아니라 instrument할 메서드 이름이다.
        // 잘못된 이름을 넣으면 HTTP는 수행돼도 nGrinder 통계에 테스트가 기록되지 않는다.
        registerTest.record(this, "registerNotification")
        // 개별 요청 지연을 nGrinder 결과에 남긴다. 전체 평균만으로 p99를 대체하지 않는다.
        grinder.statistics.delayReports = true
    }

    /**
     * 이 스크립트는 별도 pacing 없이 응답 완료 후 다음 요청을 보내는 closed-loop 부하다.
     * 따라서 설정한 thread 수는 동시 요청 수이고, 도착률은 응답 지연과 Agent 성능의 결과다.
     * 고정 RPS/open-loop 실험은 별도 rate-controller 또는 외부 load generator로 수행한다.
     */
    @Test
    void registerNotification() {
        boolean idempotencyScenario = SCENARIO == "idempotency"
        String eventId = idempotencyScenario
                ? "ngrinder-idempotency-fixed"
                : "ngrinder-s5-" + SCENARIO + "-" + RUN_ID + "-" + UUID.randomUUID()

        String payload = JsonOutput.toJson([
                receiverId      : 1001,
                notificationType: "PAYMENT_CONFIRMED",
                channel         : "EMAIL",
                channelTarget   : "load-test@example.invalid",
                eventId         : eventId,
                referenceId     : 1,
                referenceType   : "PAYMENT",
                contentData     : "{}",
                scheduledAt     : SCENARIO == "scheduled" ? scheduledAt : null
        ])

        // nGrinder 3.5.9의 HTTP plugin은 JSON String/Map overload가 아니라 byte[]와 Apache Header 목록을 받는다.
        HTTPResponse response = request.POST(
                BASE_URL + "/api/v1/notifications",
                payload.getBytes("UTF-8"),
                headers)
        int status = response.statusCode
        boolean accepted = status >= 200 && status < 300

        if (!accepted) {
            grinder.logger.warn("notification registration failed. status={}, scenario={}, body={}",
                    status, SCENARIO, response.bodyText)
            // GTest가 메서드 전체를 계측한다. 여기에서 명시적으로 실패시켜야
            // 5xx/503을 성공 TPS에 섞지 않고 Errors로 남길 수 있다.
            throw new AssertionError("notification registration failed: HTTP " + status)
        }
    }
}
