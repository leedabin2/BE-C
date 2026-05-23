package com.notification.application.port.in;

import com.notification.application.port.in.result.NotificationDetailResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface GetNotificationUseCase {

    NotificationDetailResult getById(Long notificationId, Long requesterId);

    Page<NotificationDetailResult> getByReceiver(Long requesterId, Boolean isRead, Pageable pageable);
}
