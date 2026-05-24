package com.notification.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
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

    /** Pageable sort 파라미터를 배열 대신 단일 문자열로 표시해 Swagger UI 기본값 오류를 방지한다. */
    @Bean
    public OperationCustomizer fixPageableSort() {
        return (operation, handlerMethod) -> {
            if (operation.getParameters() == null) return operation;
            operation.getParameters().stream()
                    .filter(p -> "sort".equals(p.getName()) && "query".equals(p.getIn()))
                    .forEach(p -> p.schema(new StringSchema().example("createdAt,desc")));
            return operation;
        };
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
