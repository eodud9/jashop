CREATE TABLE work_schedules (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    start_at DATETIME(6) NOT NULL,
    end_at DATETIME(6) NULL,
    status VARCHAR(24) NOT NULL,
    active_work_date DATE GENERATED ALWAYS AS (CASE WHEN status = 'SCHEDULED' THEN work_date ELSE NULL END) STORED,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    cancelled_by BIGINT NULL,
    cancelled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_work_schedules_user_active_date UNIQUE (user_id, active_work_date),
    CONSTRAINT fk_work_schedules_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_work_schedules_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_work_schedules_creator FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT fk_work_schedules_updater FOREIGN KEY (updated_by) REFERENCES users(id),
    CONSTRAINT fk_work_schedules_canceller FOREIGN KEY (cancelled_by) REFERENCES users(id),
    CONSTRAINT ck_work_schedules_date CHECK (work_date = DATE(DATE_ADD(start_at, INTERVAL 9 HOUR))),
    CONSTRAINT ck_work_schedules_time CHECK (end_at IS NULL OR
        (end_at > start_at AND DATE(DATE_ADD(end_at, INTERVAL 9 HOUR)) = work_date)),
    CONSTRAINT ck_work_schedules_status CHECK (
        (status = 'SCHEDULED' AND cancelled_by IS NULL AND cancelled_at IS NULL) OR
        (status = 'CANCELLED' AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL)),
    INDEX ix_work_schedules_store_start_id (store_id, start_at, id),
    INDEX ix_work_schedules_user_start_id (user_id, start_at, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 체크인 기록이 있는 일정의 수정/취소를 막기 위한 기반. 체크인 API는 후속 단계에서 구현한다.
CREATE TABLE attendances (
    id BIGINT NOT NULL AUTO_INCREMENT,
    work_schedule_id BIGINT NOT NULL,
    check_in_at DATETIME(6) NOT NULL,
    check_out_at DATETIME(6) NULL,
    recorded_check_out_at DATETIME(6) NULL,
    approval_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    approved_by BIGINT NULL,
    approved_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_attendances_schedule UNIQUE (work_schedule_id),
    CONSTRAINT fk_attendances_schedule FOREIGN KEY (work_schedule_id) REFERENCES work_schedules(id),
    CONSTRAINT fk_attendances_approver FOREIGN KEY (approved_by) REFERENCES users(id),
    CONSTRAINT ck_attendances_checkout CHECK (check_out_at IS NULL OR check_out_at >= check_in_at),
    CONSTRAINT ck_attendances_approval CHECK (
        (approval_status = 'PENDING' AND approved_by IS NULL AND approved_at IS NULL AND recorded_check_out_at IS NULL) OR
        (approval_status = 'APPROVED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL
         AND check_out_at IS NOT NULL AND recorded_check_out_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
