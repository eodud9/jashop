CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    home_store_id BIGINT NULL,
    approved_by BIGINT NULL,
    approved_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT fk_users_home_store FOREIGN KEY (home_store_id) REFERENCES stores (id),
    CONSTRAINT fk_users_approved_by FOREIGN KEY (approved_by) REFERENCES users (id),
    CONSTRAINT ck_users_role CHECK (role IN ('STAFF', 'ASSISTANT_MANAGER', 'MANAGER')),
    CONSTRAINT ck_users_status CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'INACTIVE')),
    CONSTRAINT ck_users_active_home_store CHECK (
        status <> 'ACTIVE' OR role = 'MANAGER' OR home_store_id IS NOT NULL
    ),
    INDEX ix_users_status_home_store_role (status, home_store_id, role)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
