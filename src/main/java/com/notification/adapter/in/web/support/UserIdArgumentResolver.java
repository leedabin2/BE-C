package com.notification.adapter.in.web.support;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class UserIdArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String HEADER = "X-User-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class)
                && parameter.getParameterType().equals(Long.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String value = webRequest.getHeader(HEADER);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("X-User-Id 헤더가 필요합니다.");
        }
        try {
            long userId = Long.parseLong(value.trim());
            if (userId <= 0) {
                throw new IllegalArgumentException("X-User-Id는 1 이상의 양수여야 합니다.");
            }
            return userId;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("X-User-Id 헤더 형식이 올바르지 않습니다: " + value);
        }
    }
}
