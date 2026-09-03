package com.notification.adapter.in.web.validation;
// PRD: F1-1 → docs/prd/F1.md

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ChannelTargetValidator.class)
@Documented
public @interface ValidChannelTarget {
    String message() default "채널 대상(channelTarget)이 채널 유형과 일치하지 않습니다.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
