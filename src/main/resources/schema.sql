CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

-- notification_log.notification_id → notification.id FK (멱등: 이미 존재하면 스킵)
SET @fk_log = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'notification_log'
    AND CONSTRAINT_NAME = 'fk_log_notification');
SET @fk_log_sql = IF(@fk_log = 0,
    'ALTER TABLE notification_log ADD CONSTRAINT fk_log_notification FOREIGN KEY (notification_id) REFERENCES notification(id) ON DELETE CASCADE',
    'SELECT 1');
PREPARE fk_log_stmt FROM @fk_log_sql;
EXECUTE fk_log_stmt;
DEALLOCATE PREPARE fk_log_stmt;

-- dispatch_history.notification_id → notification.id FK (멱등: 이미 존재하면 스킵)
SET @fk_hist = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'dispatch_history'
    AND CONSTRAINT_NAME = 'fk_history_notification');
SET @fk_hist_sql = IF(@fk_hist = 0,
    'ALTER TABLE dispatch_history ADD CONSTRAINT fk_history_notification FOREIGN KEY (notification_id) REFERENCES notification(id) ON DELETE CASCADE',
    'SELECT 1');
PREPARE fk_hist_stmt FROM @fk_hist_sql;
EXECUTE fk_hist_stmt;
DEALLOCATE PREPARE fk_hist_stmt;
