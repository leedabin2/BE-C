package com.notification.application.event;

import java.time.LocalDateTime;

public record NotificationCreatedEvent(Long notificationId, LocalDateTime scheduledAt) {}
