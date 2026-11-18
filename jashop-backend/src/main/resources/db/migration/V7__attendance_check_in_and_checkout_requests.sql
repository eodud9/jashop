ALTER TABLE attendances ADD COLUMN recorded_check_in_at DATETIME(6) NULL AFTER check_in_at;

-- 기존 실제 출근 시각을 보존하고, 인정 시각만 예정/실제 중 늦은 시각으로 보충한다.
UPDATE attendances a JOIN work_schedules s ON s.id = a.work_schedule_id
SET a.recorded_check_in_at = GREATEST(a.check_in_at, s.start_at);

ALTER TABLE attendances
    MODIFY COLUMN recorded_check_in_at DATETIME(6) NOT NULL,
    ADD CONSTRAINT ck_attendances_recorded_checkin CHECK (recorded_check_in_at >= check_in_at);

-- 성공한 체크아웃 결과만 원자적으로 저장한다. 자동 만료/삭제는 도입하지 않는다.
CREATE TABLE attendance_checkout_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_id BIGINT NOT NULL,
    response_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_checkout_requests_user_key UNIQUE (user_id, request_key),
    CONSTRAINT fk_checkout_requests_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_checkout_requests_attendance FOREIGN KEY (attendance_id) REFERENCES attendances(id),
    CONSTRAINT ck_checkout_requests_json CHECK (JSON_VALID(response_json))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
