package com.notification.application.port.in;

import com.notification.application.port.in.command.RegisterNotificationCommand;
import com.notification.application.port.in.result.RegisterNotificationResult;

public interface RegisterNotificationUseCase {
    RegisterNotificationResult register(RegisterNotificationCommand command);

    /** 동시 중복 등록 경합 시 idempotency key로 기존 알림을 조회한다. */
    RegisterNotificationResult findExistingByCommand(RegisterNotificationCommand command);
}
