package com.notification.infrastructure.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ShedLock 설정.
 *
 * 다중 인스턴스 환경에서 @Scheduled 메서드가 한 인스턴스에서만 실행되도록 보장한다.
 * DB의 shedlock 테이블을 락 저장소로 사용한다.
 *
 * usingDbTime(): 각 서버의 시스템 시계 대신 DB 시간을 기준으로 lock_until을 계산한다.
 * 서버 간 시계 오차(Clock Skew)로 인한 락 오동작을 방지한다.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "55s")
public class SchedulerConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build()
        );
    }
}
