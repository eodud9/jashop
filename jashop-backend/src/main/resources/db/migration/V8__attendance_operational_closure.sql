ALTER TABLE attendances
    ADD COLUMN closed_at DATETIME(6) NULL,
    ADD COLUMN closure_type VARCHAR(32) NULL;

-- 직원이 이미 입력한 원본/승인/멱등 응답은 유지하고 종료 정보만 보충한다.
UPDATE attendances SET closed_at = check_out_at, closure_type = 'EMPLOYEE_CHECKOUT'
WHERE check_out_at IS NOT NULL;

ALTER TABLE attendances
    DROP CHECK ck_attendances_approval,
    ADD CONSTRAINT ck_attendances_approval CHECK (
        (approval_status = 'PENDING' AND approved_by IS NULL AND approved_at IS NULL AND recorded_check_out_at IS NULL) OR
        (approval_status = 'APPROVED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL
         AND recorded_check_out_at IS NOT NULL AND closed_at IS NOT NULL)),
    ADD CONSTRAINT ck_attendances_closure CHECK (
        (closed_at IS NULL AND closure_type IS NULL AND check_out_at IS NULL AND approval_status = 'PENDING') OR
        (closed_at IS NOT NULL AND closure_type IS NOT NULL AND closure_type = 'EMPLOYEE_CHECKOUT'
         AND check_out_at IS NOT NULL AND closed_at = check_out_at AND closed_at >= check_in_at) OR
        (closed_at IS NOT NULL AND closure_type IS NOT NULL AND closure_type = 'ASSISTANT_APPROVAL' AND check_out_at IS NULL
         AND approval_status = 'APPROVED' AND approved_at IS NOT NULL AND closed_at = approved_at));
