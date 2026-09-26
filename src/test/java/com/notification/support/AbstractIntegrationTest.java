package com.notification.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.MySQLContainer;

/**
 * 통합 테스트 베이스 클래스.
 *
 * <b>싱글턴 컨테이너 패턴.</b> {@code @Testcontainers} + {@code @Container}를 쓰면 JUnit이
 * <b>테스트 클래스가 끝날 때 컨테이너를 멈춘다.</b> 그러면 두 번째 통합 테스트 클래스부터는
 * 죽은 컨테이너에 붙으려다 "Connection is not available (total=0)"으로 전부 실패한다.
 *
 * static 블록에서 직접 start()하면 JVM 하나당 한 번만 뜨고, 종료는 Testcontainers의
 * Ryuk 컨테이너가 JVM 종료 시 처리한다. 기동 비용(~15초)도 최초 1회로 제한된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// DB는 공유하지만 설정별 컨텍스트/스케줄러/Mock은 별개다. 클래스 종료 시 모두 닫아 간섭을 막는다.
// 이 베이스의 공유 DB 테스트는 병렬 실행하지 않는다 (junit-platform.properties).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("notification_test")
            .withUsername("test")
            .withPassword("test");

    static {
        mysql.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }
}
