package com.notification.application.service;

import com.notification.application.port.in.GetNotificationUseCase;
import com.notification.application.port.in.result.NotificationDetailResult;
import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.common.exception.ErrorCode;
import com.notification.common.exception.NotificationException;
import com.notification.domain.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GetNotificationService implements GetNotificationUseCase {

    private final NotificationRepositoryPort notificationRepositoryPort;

    @Override
    @Transactional(readOnly = true)
    public NotificationDetailResult getById(Long notificationId, Long requesterId) {
        Notification notification = notificationRepositoryPort.findById(notificationId)
                .orElseThrow(() -> new NotificationException(ErrorCode.NOTIFICATION_NOT_FOUND));

        // 존재 여부를 숨기기 위해 403이 아닌 404 반환
        if (!notification.getReceiverId().equals(requesterId)) {
            throw new NotificationException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }

        return NotificationDetailResult.from(notification);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationDetailResult> getByReceiver(Long requesterId, Boolean isRead, Pageable pageable) {
        return notificationRepositoryPort.findByReceiver(requesterId, isRead, pageable)
                .map(NotificationDetailResult::from);
    }
}
