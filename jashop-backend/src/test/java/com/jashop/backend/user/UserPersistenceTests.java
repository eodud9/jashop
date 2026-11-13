package com.jashop.backend.user;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.jashop.backend.store.domain.Store;
import com.jashop.backend.store.infrastructure.StoreRepository;
import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.domain.UserRole;
import com.jashop.backend.user.domain.UserStatus;
import com.jashop.backend.user.infrastructure.UserRepository;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers
class UserPersistenceTests {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("jashop_user_test")
            .withUsername("jashop_test")
            .withPassword("test-only-password")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

    private static final Instant NOW = Instant.parse("2026-11-09T01:02:03.123456Z");

    @Autowired UserRepository users;
    @Autowired StoreRepository stores;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    void savesAndReloadsPendingUserWithUtcMicroseconds() {
        User saved = saveUser(" Staff@Example.com ");
        entityManager.clear();

        User loaded = users.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getId()).isPositive();
        assertThat(loaded.getEmail()).isEqualTo("staff@example.com");
        assertThat(loaded.getPasswordHash()).isEqualTo("test-only-encoded-password");
        assertThat(loaded.getName()).isEqualTo("직원");
        assertThat(loaded.getRole()).isEqualTo(UserRole.STAFF);
        assertThat(loaded.getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(loaded.getHomeStoreId()).isNull();
        assertThat(loaded.getApprovedBy()).isNull();
        assertThat(loaded.getApprovedAt()).isNull();
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(loaded.getUpdatedAt()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("SELECT created_at FROM users WHERE id = ?",
                LocalDateTime.class, loaded.getId())).isEqualTo(LocalDateTime.parse("2026-11-09T01:02:03.123456"));
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, loaded.getId()))
                .isEqualTo("STAFF");
    }

    @Test
    void findsEmailAndChecksExistence() {
        User saved = saveUser("lookup@example.com");
        entityManager.clear();

        assertThat(users.findByEmail(User.normalizeEmail(" Lookup@Example.com ")))
                .get().extracting(User::getId).isEqualTo(saved.getId());
        assertThat(users.existsByEmail("lookup@example.com")).isTrue();
        assertThat(users.findByEmail("missing@example.com")).isEmpty();
        assertThat(users.existsByEmail("missing@example.com")).isFalse();
    }

    @Test
    void databaseRejectsDuplicateEmailAfterNormalization() {
        saveUser("duplicate@example.com");
        assertThatThrownBy(() -> saveUser(" DUPLICATE@Example.com "))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void homeStoreForeignKeyRejectsMissingStore() {
        User saved = saveUser("missing-store@example.com");
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET home_store_id = ? WHERE id = ?",
                Long.MAX_VALUE, saved.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void approverForeignKeyRejectsMissingUser() {
        User saved = saveUser("missing-approver@example.com");
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET approved_by = ? WHERE id = ?",
                Long.MAX_VALUE, saved.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reloadsStoreAndApprovalReferencesAndBlocksReferencedStoreDeletion() {
        Store store = stores.saveAndFlush(Store.create("HOME", "소속 매장", NOW));
        User manager = saveUser("manager@example.com");
        jdbc.update("UPDATE users SET role = 'MANAGER', status = 'ACTIVE' WHERE id = ?", manager.getId());
        User staff = saveUser("approved@example.com");
        LocalDateTime approvedAt = LocalDateTime.parse("2026-11-09T02:03:04.654321");
        jdbc.update("UPDATE users SET status = 'ACTIVE', home_store_id = ?, approved_by = ?, approved_at = ? "
                + "WHERE id = ?", store.getId(), manager.getId(), approvedAt, staff.getId());
        entityManager.clear();

        User loaded = users.findById(staff.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(loaded.getHomeStoreId()).isEqualTo(store.getId());
        assertThat(loaded.getApprovedBy()).isEqualTo(manager.getId());
        assertThat(loaded.getApprovedAt()).isEqualTo(Instant.parse("2026-11-09T02:03:04.654321Z"));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM stores WHERE id = ?", store.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", manager.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STAFF", "ASSISTANT_MANAGER"})
    void databaseRequiresHomeStoreForActiveEmployees(String role) {
        User saved = saveUser("no-home@example.com");
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET role = ?, status = 'ACTIVE' WHERE id = ?",
                role, saved.getId())).isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_users_active_home_store")
                .satisfies(error -> assertThat(((SQLException) error.getCause()).getErrorCode()).isEqualTo(3819));
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "status"})
    void databaseRejectsUnknownEnumValues(String column) {
        User saved = saveUser("invalid-enum@example.com");
        // column은 테스트에 고정된 role/status만 허용한다.
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET " + column + " = 'UNKNOWN' WHERE id = ?", saved.getId()))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_users_" + column)
                .satisfies(error -> assertThat(((SQLException) error.getCause()).getErrorCode()).isEqualTo(3819));
    }

    @Test
    void upgradesV1WithoutLosingStoreAndReexecutesV2WithoutLosingUser() throws Exception {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = mysql.getJdbcUrl().replace("/jashop_user_test", "/" + schema);
        Flyway v1 = Flyway.configure().dataSource(url, "root", mysql.getPassword())
                .locations("classpath:db/migration").target("1").cleanDisabled(true).load();
        assertThat(v1.migrate().migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(url, "root", mysql.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO stores(code,name,created_at,updated_at) "
                    + "VALUES ('PRESERVE','기존 매장','2026-11-09 01:02:03','2026-11-09 01:02:03')");
        }
        Flyway upgraded = Flyway.configure().dataSource(url, "root", mysql.getPassword())
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(5);
        try (var connection = DriverManager.getConnection(url, "root", mysql.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users(email,password_hash,name,role,status,created_at,updated_at) "
                    + "VALUES ('preserve@example.com','test-hash','보존 직원','STAFF','PENDING',"
                    + "'2026-11-09 01:02:03','2026-11-09 01:02:03')");
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            upgraded.validate();
            try (var rows = statement.executeQuery("SELECT name FROM stores WHERE code = 'PRESERVE'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("기존 매장");
            }
            try (var rows = statement.executeQuery("SELECT status FROM users WHERE email = 'preserve@example.com'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("PENDING");
            }
        }
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");
    }

    private User saveUser(String email) {
        return users.saveAndFlush(User.createPending(email, "test-only-encoded-password", "직원", NOW));
    }
}
