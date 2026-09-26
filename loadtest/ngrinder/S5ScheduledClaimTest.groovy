import static net.grinder.script.Grinder.grinder

import groovy.json.JsonOutput
import net.grinder.script.GTest
import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread
import org.apache.hc.core5.http.Header
import org.apache.hc.core5.http.message.BasicHeader
import org.junit.Test
import org.junit.runner.RunWith
import org.ngrinder.http.HTTPRequest
import org.ngrinder.http.HTTPResponse

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * S5-N3 — 세 JVM이 같은 DB의 예약 작업을 claim하는지를 확인하는 전용 스크립트.
 *
 * 기존 NotificationRegistrationTest와 파일/클래스 이름을 분리한다. nGrinder Agent의
 * 이전 script cache 때문에 scheduledAt이 누락되는 것을 방지하기 위한 검증용이다.
 * 이 테스트는 처리량 측정이 아니라 아래 세 가지를 확인한다.
 * 1) 모든 등록 행에 scheduled_at이 저장되는가
 * 2) due 이후 N=3 인스턴스가 동일 작업을 중복 발송하지 않는가
 * 3) 모든 작업이 최종 SENT가 되는가
 */
@RunWith(GrinderRunner)
class S5ScheduledClaimTest {

    private static final String BASE_URL = System.getProperty(
            "notification.base-url", "http://host.docker.internal:8080")
    private static final String RUN_ID = UUID.randomUUID().toString()
    private static final String EVENT_PREFIX = "ngrinder-s5-n3-scheduled-"

    private static GTest registerTest
    private static HTTPRequest request
    private static List<Header> headers
    private static String scheduledAt

    @BeforeProcess
    static void beforeProcess() {
        registerTest = new GTest(1, "POST scheduled notification")
        request = new HTTPRequest()
        headers = [
                new BasicHeader("Content-Type", "application/json"),
                new BasicHeader("X-User-Id", "1001")
        ]

        // script 준비/등록 60초보다 뒤에 due가 되도록 둔다. 애플리케이션과 MySQL은 UTC 기준이다.
        scheduledAt = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(120).withNano(0).toString()
        grinder.logger.info("S5 N=3 scheduled-claim test starts. baseUrl={}, runId={}, scheduledAt={}",
                BASE_URL, RUN_ID, scheduledAt)
    }

    @BeforeThread
    void beforeThread() {
        registerTest.record(this, "registerScheduledNotification")
        grinder.statistics.delayReports = true
    }

    @Test
    void registerScheduledNotification() {
        String payload = JsonOutput.toJson([
                receiverId      : 1001,
                notificationType: "PAYMENT_CONFIRMED",
                channel         : "EMAIL",
                channelTarget   : "load-test@example.invalid",
                eventId         : EVENT_PREFIX + RUN_ID + "-" + UUID.randomUUID(),
                referenceId     : 1,
                referenceType   : "PAYMENT",
                contentData     : "{}",
                scheduledAt     : scheduledAt
        ])

        HTTPResponse response = request.POST(
                BASE_URL + "/api/v1/notifications",
                payload.getBytes("UTF-8"),
                headers)
        int status = response.statusCode
        if (status < 200 || status >= 300) {
            grinder.logger.warn("scheduled registration failed. status={}, body={}", status, response.bodyText)
            throw new AssertionError("scheduled registration failed: HTTP " + status)
        }
    }
}
