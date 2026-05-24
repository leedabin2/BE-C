package com.notification.adapter.in.web.validation;

import com.notification.adapter.in.web.dto.NotificationRequest;
import com.notification.domain.NotificationChannel;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class ChannelTargetValidator implements ConstraintValidator<ValidChannelTarget, NotificationRequest> {

    // RFC 5321 기반 이메일 형식 검증 (간소화)
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Override
    public boolean isValid(NotificationRequest request, ConstraintValidatorContext context) {
        if (request == null || request.channel() == null) return true;

        if (request.channel() == NotificationChannel.EMAIL) {
            String target = request.channelTarget();
            if (target == null || target.isBlank()) {
                setMessage(context, "EMAIL 채널은 channelTarget(이메일 주소)이 필요합니다.");
                return false;
            }
            if (!EMAIL_PATTERN.matcher(target).matches()) {
                setMessage(context, "EMAIL 채널의 channelTarget은 올바른 이메일 형식이어야 합니다: " + target);
                return false;
            }
        }

        if (request.channel() == NotificationChannel.IN_APP) {
            if (request.channelTarget() != null) {
                setMessage(context, "IN_APP 채널은 channelTarget이 null이어야 합니다.");
                return false;
            }
        }

        return true;
    }

    private void setMessage(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode("channelTarget")
                .addConstraintViolation();
    }
}
