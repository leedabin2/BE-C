package com.notification.application.port.in;
// PRD: F1-1, F4-2 (진입점 수렴 지점) → docs/prd/F1.md, docs/prd/F4.md

import com.notification.application.port.in.command.RegisterNotificationCommand;
import com.notification.application.port.in.result.RegisterNotificationResult;

public interface RegisterNotificationUseCase {

    /** 인그레스(HTTP·메시징)가 부르는 유일한 메서드. 경합 처리는 구현체 안에 있다. */
    RegisterNotificationResult register(RegisterNotificationCommand command);
}
