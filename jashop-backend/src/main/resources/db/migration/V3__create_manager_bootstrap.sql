-- 초기 관리자 생성은 이 단일 행을 잠근 트랜잭션에서만 수행한다.
CREATE TABLE manager_bootstrap (
    id TINYINT NOT NULL,
    user_id BIGINT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_manager_bootstrap_singleton CHECK (id = 1),
    CONSTRAINT ck_manager_bootstrap_completion CHECK (
        (user_id IS NULL AND completed_at IS NULL) OR
        (user_id IS NOT NULL AND completed_at IS NOT NULL)
    ),
    CONSTRAINT fk_manager_bootstrap_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO manager_bootstrap (id) VALUES (1);
