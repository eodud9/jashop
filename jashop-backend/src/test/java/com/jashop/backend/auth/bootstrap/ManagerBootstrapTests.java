package com.jashop.backend.auth.bootstrap;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.jashop.backend.auth.api.dto.SignupRequest;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.user.application.UserAuthorizationService;
import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.domain.UserRole;
import com.jashop.backend.user.domain.UserStatus;
import com.jashop.backend.user.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ManagerBootstrapTests {
    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withCommand("--log-bin-trust-function-creators=1"); // 롤백 검증용 트리거에 한정한 테스트 설정

    @Autowired ManagerBootstrapService bootstrap;
    @Autowired UserAuthorizationService authorization;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ApplicationContext context;

    @BeforeEach
    void resetIsolatedTestDatabase() {
        jdbc.update("UPDATE manager_bootstrap SET user_id = NULL, completed_at = NULL WHERE id = 1");
        users.deleteAll();
    }

    @Test
    void disabledByDefaultAndCreatesHashedActiveManagerWithoutHomeStore() {
        assertThat(context.getBeansOfType(ManagerBootstrapRunner.class)).isEmpty();
        bootstrap.create(request(" Manager@Example.com "));
        User manager = users.findByEmail("manager@example.com").orElseThrow();
        assertThat(manager.getRole()).isEqualTo(UserRole.MANAGER);
        assertThat(manager.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(manager.getHomeStoreId()).isNull();
        assertThat(manager.getApprovedBy()).isNull();
        assertThat(manager.getApprovedAt()).isNull();
        assertThat(encoder.matches("test-only-password", manager.getPasswordHash())).isTrue();
        assertThat(jdbc.queryForObject("SELECT user_id FROM manager_bootstrap WHERE id = 1", Long.class))
                .isEqualTo(manager.getId());
        assertThat(jdbc.queryForObject("SELECT completed_at FROM manager_bootstrap WHERE id = 1", java.time.LocalDateTime.class))
                .isNotNull();
        assertThat(authorization.requireManager(manager.getId()).getId()).isEqualTo(manager.getId());
        jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", manager.getId());
        denied(() -> authorization.requireManager(manager.getId()), ErrorCode.ACCOUNT_INACTIVE);
        denied(() -> bootstrap.create(request("another@example.com")), ErrorCode.BOOTSTRAP_NOT_ALLOWED);
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void neverPromotesExistingPendingAccountAndCanRetryAfterFailure() {
        User pending = users.saveAndFlush(User.createPending("same@example.com", "original-hash", "직원", Instant.now()));
        denied(() -> bootstrap.create(request("same@example.com")), ErrorCode.DUPLICATE_EMAIL);
        assertThat(users.findById(pending.getId()).orElseThrow().getRole()).isEqualTo(UserRole.STAFF);
        assertThat(jdbc.queryForObject("SELECT user_id FROM manager_bootstrap WHERE id = 1", Long.class)).isNull();
        bootstrap.create(request("other@example.com"));
        assertThat(users.count()).isEqualTo(2);
    }

    @Test
    void refusesExistingManagerEvenWithoutBootstrapRecord() {
        users.saveAndFlush(User.createInitialManager("existing@example.com", "existing-hash", "운영자", Instant.now()));
        denied(() -> bootstrap.create(request("new@example.com")), ErrorCode.BOOTSTRAP_NOT_ALLOWED);
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void invalidCredentialsDoNotConsumeBootstrapOrLeakPassword() {
        assertThatThrownBy(() -> bootstrap.create(new SignupRequest("운영자", "bad-email", "short")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("short").hasMessageNotContaining("bad-email");
        assertThat(users.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT user_id FROM manager_bootstrap WHERE id = 1", Long.class)).isNull();
    }

    @Test
    void storeAuthorizationUsesPersistedRoleAndHomeStoreChanges() {
        jdbc.update("INSERT INTO stores(code,name,active,created_at,updated_at) VALUES "
                + "('AUTH_STORE','인가 매장',true,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)) "
                + "ON DUPLICATE KEY UPDATE name = '인가 매장'");
        Long storeId = jdbc.queryForObject("SELECT id FROM stores WHERE code = 'AUTH_STORE'", Long.class);
        User user = users.saveAndFlush(User.createPending("assistant@example.com", "test-hash", "부매니저", Instant.now()));
        jdbc.update("UPDATE users SET status = 'ACTIVE', role = 'ASSISTANT_MANAGER', home_store_id = ? WHERE id = ?",
                storeId, user.getId());
        assertThat(authorization.requireStoreAssistantManager(user.getId(), storeId).getId()).isEqualTo(user.getId());
        denied(() -> authorization.requireStoreAssistantManager(user.getId(), storeId + 1), ErrorCode.STORE_ACCESS_DENIED);
        jdbc.update("UPDATE users SET role = 'STAFF' WHERE id = ?", user.getId());
        denied(() -> authorization.requireStoreAssistantManager(user.getId(), storeId), ErrorCode.STORE_ACCESS_DENIED);
    }

    @Test
    void concurrentDifferentEmailsCreateOnlyOneManager() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> attempt(start, "first@example.com"));
            var second = executor.submit(() -> attempt(start, "second@example.com"));
            start.countDown();
            assertThat(java.util.List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("created", "BOOTSTRAP_NOT_ALLOWED");
        }
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void completionFailureRollsBackUserAndAllowsRetry() {
        jdbc.execute("CREATE TRIGGER fail_bootstrap BEFORE UPDATE ON manager_bootstrap FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test-only-failure'");
        try {
            assertThatThrownBy(() -> bootstrap.create(request("rollback@example.com")))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(users.count()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_bootstrap");
        }
        bootstrap.create(request("rollback@example.com"));
        assertThat(users.count()).isEqualTo(1);
    }

    private String attempt(CountDownLatch start, String email) throws InterruptedException {
        start.await();
        try {
            bootstrap.create(request(email));
            return "created";
        } catch (BusinessException error) {
            return error.getErrorCode().name();
        }
    }

    private SignupRequest request(String email) {
        return new SignupRequest("운영자", email, "test-only-password");
    }

    private void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
