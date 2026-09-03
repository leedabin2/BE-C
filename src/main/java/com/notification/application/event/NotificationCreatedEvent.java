package com.notification.application.event;
// PRD: F4-1, O-1 → docs/prd/F4.md, docs/prd/O1.md

import java.time.LocalDateTime;

public record NotificationCreatedEvent(Long notificationId, LocalDateTime scheduledAt) {}
