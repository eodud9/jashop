CREATE TABLE checkout_notices (
    id BIGINT NOT NULL AUTO_INCREMENT,
    work_schedule_id BIGINT NOT NULL,
    planned_check_out_at DATETIME(6) NOT NULL,
    sent_by BIGINT NOT NULL,
    sent_at DATETIME(6) NOT NULL,
    acknowledged_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_checkout_notices_schedule FOREIGN KEY (work_schedule_id) REFERENCES work_schedules(id),
    CONSTRAINT fk_checkout_notices_sender FOREIGN KEY (sent_by) REFERENCES users(id),
    CONSTRAINT ck_checkout_notices_future CHECK (planned_check_out_at > sent_at),
    CONSTRAINT ck_checkout_notices_time_unit CHECK (
        MINUTE(planned_check_out_at) IN (0, 30) AND SECOND(planned_check_out_at) = 0 AND MICROSECOND(planned_check_out_at) = 0),
    CONSTRAINT ck_checkout_notices_ack CHECK (acknowledged_at IS NULL OR acknowledged_at >= sent_at),
    INDEX ix_checkout_notices_schedule_sent_id (work_schedule_id, sent_at, id),
    INDEX ix_checkout_notices_sent_id (sent_at, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
