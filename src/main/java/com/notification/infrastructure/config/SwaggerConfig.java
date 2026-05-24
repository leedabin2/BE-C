package com.notification.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Notification API")
                        .version("v1")
                        .description("알림 발송 시스템 API 명세"));
    }

    /** 모든 API 엔드포인트에 X-User-Id 헤더 입력란을 추가한다. */
    @Bean
    public OperationCustomizer addUserIdHeader() {
        return (operation, handlerMethod) -> {
            operation.addParametersItem(
                    new Parameter()
                            .in("header")
                            .name("X-User-Id")
                            .description("인증된 사용자 ID (1 이상의 양수)")
                            .required(true)
                            .example("1")
            );
            return operation;
        };
    }
}
