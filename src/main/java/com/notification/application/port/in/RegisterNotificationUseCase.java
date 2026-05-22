package com.notification.application.port.in;

import com.notification.application.port.in.command.RegisterNotificationCommand;
import com.notification.application.port.in.result.RegisterNotificationResult;

public interface RegisterNotificationUseCase {
    RegisterNotificationResult register(RegisterNotificationCommand command);
}
