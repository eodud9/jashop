package com.jashop.backend.store;

import java.time.Instant;
import java.time.LocalDateTime;
import java.sql.DriverManager;
import java.util.UUID;

import com.jashop.backend.store.domain.Store;
import com.jashop.backend.store.infrastructure.StoreRepository;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers
class StorePersistenceTests {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("jashop_test")
            .withUsername("jashop_test")
            .withPassword("test-only-password")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

    private static final Instant CREATED_AT = Instant.parse("2026-11-07T03:12:34.123456Z");

    @Autowired
    StoreRepository stores;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Flyway flyway;

    @Test
    void savesAndReloadsStoreWithGeneratedIdAndUtcMicroseconds() {
        Store saved = stores.saveAndFlush(Store.create("STORE-01", "첫 번째 매장", CREATED_AT));
        Long id = saved.getId();
        entityManager.clear();

        Store loaded = stores.findById(id).orElseThrow();
        assertThat(id).isPositive();
        assertThat(loaded.getCode()).isEqualTo("STORE-01");
        assertThat(loaded.getName()).isEqualTo("첫 번째 매장");
        assertThat(loaded.isActive()).isTrue();
        assertThat(loaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(loaded.getUpdatedAt()).isEqualTo(CREATED_AT);
        assertThat(jdbc.queryForObject("SELECT created_at FROM stores WHERE id = ?", LocalDateTime.class, id))
                .isEqualTo(LocalDateTime.parse("2026-11-07T03:12:34.123456"));
    }

    @Test
    void findsStoreByCodeAndChecksExistence() {
        Store saved = stores.saveAndFlush(Store.create("LOOKUP", "조회 매장", CREATED_AT));
        entityManager.clear();

        assertThat(stores.findByCode("LOOKUP")).get().extracting(Store::getId).isEqualTo(saved.getId());
        assertThat(stores.existsByCode("LOOKUP")).isTrue();
        assertThat(stores.findByCode("MISSING")).isEmpty();
        assertThat(stores.existsByCode("MISSING")).isFalse();
    }

    @Test
    void mysqlRejectsDuplicateCode() {
        stores.saveAndFlush(Store.create("UNIQUE", "기존 매장", CREATED_AT));
        assertThatThrownBy(() -> stores.saveAndFlush(Store.create("UNIQUE", "중복 매장", CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void dirtyCheckingPersistsRenameWithoutChangingCodeOrCreationTime() {
        Store saved = stores.saveAndFlush(Store.create("RENAME", "변경 전", CREATED_AT));
        Instant changedAt = CREATED_AT.plusSeconds(60);
        saved.rename("변경 후", changedAt);
        entityManager.flush();
        entityManager.clear();

        Store loaded = stores.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("변경 후");
        assertThat(loaded.getCode()).isEqualTo("RENAME");
        assertThat(loaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(loaded.getUpdatedAt()).isEqualTo(changedAt);
    }

    @Test
    void deactivationPreservesRowAndRepeatedDeactivationPreservesTimestamp() {
        Store saved = stores.saveAndFlush(Store.create("INACTIVE", "보존 매장", CREATED_AT));
        Instant changedAt = CREATED_AT.plusSeconds(60);
        saved.deactivate(changedAt);
        saved.deactivate(changedAt.plusSeconds(60));
        entityManager.flush();
        entityManager.clear();

        Store loaded = stores.findById(saved.getId()).orElseThrow();
        assertThat(loaded.isActive()).isFalse();
        assertThat(loaded.getName()).isEqualTo("보존 매장");
        assertThat(loaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(loaded.getUpdatedAt()).isEqualTo(changedAt);
        assertThat(stores.existsByCode("INACTIVE")).isTrue();
    }

    @Test
    void migrationReexecutionIsNoOpAndPreservesCommittedData() {
        // 테스트 트랜잭션 밖 JDBC 연결로 커밋해 실제 migrate() 재실행 전후를 비교한다.
        String code = "MIG-" + UUID.randomUUID().toString().substring(0, 8);
        try (var connection = mysql.createConnection("")) {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO stores(code, name, created_at, updated_at) VALUES (?, ?, ?, ?)")) {
                statement.setString(1, code);
                statement.setString(2, "마이그레이션 보존 검증");
                statement.setObject(3, LocalDateTime.ofInstant(CREATED_AT, java.time.ZoneOffset.UTC));
                statement.setObject(4, LocalDateTime.ofInstant(CREATED_AT, java.time.ZoneOffset.UTC));
                statement.executeUpdate();
            }
            var result = flyway.migrate();
            assertThat(result.migrationsExecuted).isZero();
            flyway.validate();
            try (var statement = connection.prepareStatement("SELECT name, active FROM stores WHERE code = ?")) {
                statement.setString(1, code);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("name")).isEqualTo("마이그레이션 보존 검증");
                    assertThat(rows.getBoolean("active")).isTrue();
                }
            }
        } catch (java.sql.SQLException exception) {
            throw new AssertionError(exception);
        }
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void schemaUsesInnoDbUtf8mb4AndNonNullableColumns() {
        assertThat(jdbc.queryForObject("SELECT ENGINE FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stores'", String.class)).isEqualTo("InnoDB");
        assertThat(jdbc.queryForObject("SELECT TABLE_COLLATION FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stores'", String.class))
                .isEqualTo("utf8mb4_0900_ai_ci");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stores' AND IS_NULLABLE = 'NO'", Integer.class))
                .isEqualTo(6);
        assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
    }

    @Test
    void refusesToAdoptNonEmptySchemaWithoutHistoryAndPreservesExistingData() throws Exception {
        // 기존 Compose DB가 아닌, 이 테스트 컨테이너 안에만 레거시 DB 상황을 재현한다.
        String schema = "legacy_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema);
            statement.execute("CREATE TABLE " + schema + ".legacy_data (id BIGINT PRIMARY KEY, note VARCHAR(80))");
            statement.execute("INSERT INTO " + schema + ".legacy_data VALUES (1, 'preserve-existing-data')");
        }
        String url = mysql.getJdbcUrl().replace("/jashop_test", "/" + schema);
        Flyway legacy = Flyway.configure().dataSource(url, "root", mysql.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(false).cleanDisabled(true).load();
        assertThatThrownBy(legacy::migrate).isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty schema");

        try (var connection = DriverManager.getConnection(url, "root", mysql.getPassword());
                var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT note FROM legacy_data WHERE id = 1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("preserve-existing-data");
            }
            try (var rows = statement.executeQuery("SHOW TABLES")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("legacy_data");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void detectsChecksumMismatchWithoutChangingAppliedMigration() {
        Flyway tampered = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/tampered-migration")
                .baselineOnMigrate(false).cleanDisabled(true).load();
        var validation = tampered.validateWithResult();
        assertThat(validation.validationSuccessful).isFalse();
        assertThat(validation.invalidMigrations).anySatisfy(migration ->
                assertThat(migration.errorDetails.errorCode.toString()).isEqualTo("CHECKSUM_MISMATCH"));
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
    }
}
