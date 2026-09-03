package com.notification.application.port.in;
// PRD: F1-1, F4-2 (진입점 수렴 지점) → docs/prd/F1.md, docs/prd/F4.md

import com.notification.application.port.in.command.RegisterNotificationCommand;
import com.notification.application.port.in.result.RegisterNotificationResult;

public interface RegisterNotificationUseCase {
    RegisterNotificationResult register(RegisterNotificationCommand command);

    /** 동시 중복 등록 경합 시 idempotency key로 기존 알림을 조회한다. */
    RegisterNotificationResult findExistingByCommand(RegisterNotificationCommand command);
}
