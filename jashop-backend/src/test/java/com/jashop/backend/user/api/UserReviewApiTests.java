package com.jashop.backend.user.api;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.global.security.UserPrincipal;
import com.jashop.backend.store.domain.Store;
import com.jashop.backend.store.infrastructure.StoreRepository;
import com.jashop.backend.user.api.dto.ApproveUserRequest;
import com.jashop.backend.user.application.UserReviewService;
import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.domain.UserRole;
import com.jashop.backend.user.domain.UserStatus;
import com.jashop.backend.user.infrastructure.UserRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(UserReviewApiTests.FixedClock.class)
@Testcontainers
class UserReviewApiTests {
    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    static final Instant NOW = Instant.parse("2026-11-10T03:04:05.123456Z");
    static final Instant CREATED = NOW.minusSeconds(60);
    static final String BASE = "/api/v1/admin/users";
    static final String HASH = "test-only-private-password-hash";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired StoreRepository stores;
    @Autowired UserReviewService reviews;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    Long managerId;
    Long anotherManagerId;
    Long staffId;
    Long pendingId;
    Long storeId;
    Long inactiveStoreId;

    @BeforeEach
    void prepareIsolatedTestDatabase() {
        jdbc.update("UPDATE users SET approved_by = NULL");
        users.deleteAll();
        stores.deleteAll();
        storeId = stores.saveAndFlush(Store.create("ACTIVE", "활성 매장", CREATED)).getId();
        Store inactive = Store.create("INACTIVE", "비활성 매장", CREATED);
        inactive.deactivate(CREATED);
        inactiveStoreId = stores.saveAndFlush(inactive).getId();
        managerId = users.saveAndFlush(User.createInitialManager("manager@example.com", HASH, "총괄", CREATED)).getId();
        anotherManagerId = users.saveAndFlush(User.createInitialManager("another@example.com", HASH, "총괄2", CREATED)).getId();
        User staff = User.createPending("staff@example.com", HASH, "직원", CREATED);
        staff.approve(UserRole.STAFF, storeId, managerId, CREATED);
        staffId = users.saveAndFlush(staff).getId();
        pendingId = pending("pending@example.com");
    }

    @Test
    void anonymousAndSpoofedIdentityCannotUseAdminEndpoints() throws Exception {
        mvc.perform(get(BASE + "/pending").header("X-User-Id", managerId))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty()).andExpect(jsonPath("$.fieldErrors").isEmpty());
        mvc.perform(post(BASE + "/{id}/reject", pendingId).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/pending").with(user("manager").roles("MANAGER")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/pending").with(httpBasic("manager@example.com", HASH)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/pending").header("Authorization", "Bearer forged"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedTokenAndUnknownPrincipalAreRejected() throws Exception {
        mvc.perform(get(BASE + "/pending").with(authentication(
                        UsernamePasswordAuthenticationToken.unauthenticated(new UserPrincipal(managerId), null))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/pending").with(actor(Long.MAX_VALUE)))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"STAFF", "ASSISTANT_MANAGER"})
    void employeesCannotListApproveOrRejectEvenForHomeStore(String role) throws Exception {
        jdbc.update("UPDATE users SET role = ? WHERE id = ?", role, staffId);
        mvc.perform(get(BASE + "/pending").with(actor(staffId)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(post(BASE + "/{id}/approve", pendingId).with(actor(staffId)).with(csrf())
                        .header("X-User-Id", managerId).param("userId", managerId.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(approval("STAFF", storeId)))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/{id}/reject", pendingId).with(actor(staffId)).with(csrf()))
                .andExpect(status().isForbidden());
        assertPendingUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED", "INACTIVE"})
    void nonActiveManagerIsDenied(String status) throws Exception {
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", status, managerId);
        mvc.perform(get(BASE + "/pending").with(actor(managerId)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_" + status));
        mvc.perform(post(BASE + "/{id}/approve", pendingId).with(actor(managerId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(approval("STAFF", storeId)))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/{id}/reject", pendingId).with(actor(managerId)).with(csrf()))
                .andExpect(status().isForbidden());
        assertPendingUnchanged();
    }

    @Test
    void csrfIsRequiredForBothMutations() throws Exception {
        mvc.perform(post(BASE + "/{id}/approve", pendingId).with(actor(managerId))
                        .contentType(MediaType.APPLICATION_JSON).content(approval("STAFF", storeId)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(post(BASE + "/{id}/reject", pendingId).with(actor(managerId)))
                .andExpect(status().isForbidden());
        assertPendingUnchanged();
    }

    @Test
    void pendingListIsPagedSortedAndExcludesReviewedAccountsAndHashes() throws Exception {
        Long second = pending("second@example.com");
        Long third = pending("third@example.com");
        User rejected = User.createPending("rejected@example.com", HASH, "거절", CREATED);
        rejected.reject("이전 거절", CREATED);
        users.saveAndFlush(rejected);
        var result = mvc.perform(get(BASE + "/pending").with(actor(managerId)).param("sort", "id,desc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[0].id").value(pendingId))
                .andExpect(jsonPath("$.content[1].id").value(second))
                .andExpect(jsonPath("$.content[2].id").value(third))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3)).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(HASH, "passwordHash", "password");
        mvc.perform(get(BASE + "/pending").with(actor(managerId)).param("page", "1").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(third)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get(BASE + "/pending").with(actor(managerId)).param("page", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "page,bad", "size,0", "size,101", "page,2147483647"})
    void invalidPagingIsBadRequest(String parameter, String value) throws Exception {
        mvc.perform(get(BASE + "/pending").with(actor(managerId)).param(parameter, value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"STAFF", "ASSISTANT_MANAGER"})
    void managerApprovesAllowedRolesAndRecordsServerApproval(String role) throws Exception {
        var response = mvc.perform(post(BASE + "/{id}/approve", pendingId).with(actor(managerId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(approval(role, storeId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.role").value(role)).andExpect(jsonPath("$.homeStoreId").value(storeId))
                .andExpect(jsonPath("$.approvedBy").value(managerId)).andExpect(jsonPath("$.approvedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.createdAt").value(CREATED.toString())).andExpect(jsonPath("$.updatedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.rejectionReason").isEmpty()).andReturn();
        User saved = users.findById(pendingId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getApprovedAt()).isEqualTo(NOW);
        assertThat(saved.getApprovedBy()).isEqualTo(managerId);
        assertThat(saved.getPasswordHash()).isEqualTo(HASH);
        assertThat(response.getResponse().getContentAsString()).doesNotContain(HASH, "password");
        mvc.perform(get(BASE + "/pending").with(actor(managerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void missingOrInactiveStoreCannotBeApprovedAndLeavesUserPending() throws Exception {
        mvc.perform(post(BASE + "/{id}/approve", pendingId).with(actor(managerId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(approval("STAFF", Long.MAX_VALUE)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        mvc.perform(post(BASE + "/{id}/approve", pendingId).with(actor(managerId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(approval("STAFF", inactiveStoreId)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORE_INACTIVE"));
        assertPendingUnchanged();
    }

    @Test
    void serviceAlsoRejectsInvalidDirectInputs() {
        assertThatThrownBy(() -> reviews.approve(managerId, pendingId, new ApproveUserRequest(UserRole.MANAGER, storeId)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> reviews.approve(managerId, pendingId, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> reviews.reject(managerId, pendingId, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> reviews.pending(managerId, -1, 20)).isInstanceOf(BusinessException.class);
        assertPendingUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"role\":\"STAFF\"}", "{\"homeStoreId\":1}",
            "{\"role\":\"MANAGER\",\"homeStoreId\":1}", "{\"role\":\"ADMIN\",\"homeStoreId\":1}",
            "{\"role\":\"STAFF\",\"homeStoreId\":0}", "{\"role\":\"STAFF\",\"homeStoreId\":-1}",
            "{\"role\":\"STAFF\",\"homeStoreId\":1,\"approvedBy\":1}",
            "{\"role\":\"STAFF\",\"homeStoreId\":1,\"status\":\"ACTIVE\"}",
            "{\"role\":\"STAFF\",\"homeStoreId\":1,\"approvedAt\":\"2026-01-01T00:00:00Z\"}", "null", "{"})
    void invalidApprovalAndServerOwnedFieldsAreRejected(String body) throws Exception {
        mvc.perform(post(BASE + "/{id}/approve", pendingId).with(actor(managerId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertPendingUnchanged();
    }

    @Test
    void rejectsWithPersistedOptionalReasonWithoutTouchingApprovalOrCredentials() throws Exception {
        var response = mvc.perform(post(BASE + "/{id}/reject", pendingId).with(actor(managerId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"중복 신청\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("중복 신청"))
                .andExpect(jsonPath("$.approvedBy").isEmpty()).andExpect(jsonPath("$.approvedAt").isEmpty())
                .andExpect(jsonPath("$.homeStoreId").isEmpty()).andExpect(jsonPath("$.updatedAt").value(NOW.toString()))
                .andReturn();
        User saved = users.findById(pendingId).orElseThrow();
        assertThat(saved.getRejectionReason()).isEqualTo("중복 신청");
        assertThat(saved.getPasswordHash()).isEqualTo(HASH);
        assertThat(saved.getRole()).isEqualTo(UserRole.STAFF);
        assertThat(response.getResponse().getContentAsString()).doesNotContain(HASH, "password");
        assertThat(new com.jashop.backend.user.api.dto.RejectUserRequest("중복 신청").toString()).doesNotContain("중복 신청");
    }

    @Test
    void rejectionBodyAndReasonAreOptional() throws Exception {
        mvc.perform(post(BASE + "/{id}/reject", pendingId).with(actor(managerId)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rejectionReason").isEmpty());
        Long another = pending("empty-reason@example.com");
        mvc.perform(post(BASE + "/{id}/reject", another).with(actor(managerId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectionReasonHasBoundaryAndRejectsUnknownFields() throws Exception {
        mvc.perform(post(BASE + "/{id}/reject", pendingId).with(actor(managerId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + "가".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/{id}/reject", pendingId).with(actor(managerId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"사유\",\"userId\":1}"))
                .andExpect(status().isBadRequest());
        assertPendingUnchanged();
        mvc.perform(post(BASE + "/{id}/reject", pendingId).with(actor(managerId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + "가".repeat(500) + "\"}"))
                .andExpect(status().isOk());
        assertThat(users.findById(pendingId).orElseThrow().getRejectionReason()).hasSize(500);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "REJECTED", "INACTIVE"})
    void reviewedUsersCannotBeApprovedOrRejectedAgain(String status) throws Exception {
        jdbc.update("UPDATE users SET status = ?, home_store_id = ? WHERE id = ?", status, storeId, pendingId);
        mvc.perform(post(BASE + "/{id}/approve", pendingId).with(actor(managerId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(approval("ASSISTANT_MANAGER", storeId)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER_NOT_PENDING"));
        mvc.perform(post(BASE + "/{id}/reject", pendingId).with(actor(managerId)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER_NOT_PENDING"));
        assertThat(users.findById(pendingId).orElseThrow().getStatus().name()).isEqualTo(status);
    }

    @Test
    void successfulReviewCannotBeOverwrittenByAnotherManager() throws Exception {
        reviews.approve(managerId, pendingId, new ApproveUserRequest(UserRole.STAFF, storeId));
        mvc.perform(post(BASE + "/{id}/approve", pendingId).with(actor(anotherManagerId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(approval("ASSISTANT_MANAGER", storeId)))
                .andExpect(status().isConflict());
        mvc.perform(post(BASE + "/{id}/reject", pendingId).with(actor(anotherManagerId)).with(csrf()))
                .andExpect(status().isConflict());
        User saved = users.findById(pendingId).orElseThrow();
        assertThat(saved.getApprovedBy()).isEqualTo(managerId);
        assertThat(saved.getRole()).isEqualTo(UserRole.STAFF);
    }

    @Test
    void unknownAndInvalidTargetIdsHaveExpectedErrors() throws Exception {
        mvc.perform(post(BASE + "/{id}/approve", Long.MAX_VALUE).with(actor(managerId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(approval("STAFF", storeId)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        mvc.perform(post(BASE + "/{id}/reject", Long.MAX_VALUE).with(actor(managerId)).with(csrf()))
                .andExpect(status().isNotFound());
        for (String id : List.of("0", "-1", "bad")) {
            mvc.perform(post(BASE + "/{id}/reject", id).with(actor(managerId)).with(csrf()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void concurrentApproveAndRejectHaveExactlyOneWinner() throws Exception {
        race(false);
    }

    @Test
    void concurrentApprovalsHaveExactlyOneWinner() throws Exception {
        race(true);
    }

    private void race(boolean bothApprove) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var approve = executor.submit(() -> {
                start.await();
                return mvc.perform(post(BASE + "/{id}/approve", pendingId).with(actor(managerId)).with(csrf())
                                .contentType(MediaType.APPLICATION_JSON).content(approval("STAFF", storeId)))
                        .andReturn().getResponse().getStatus();
            });
            var other = executor.submit(() -> {
                start.await();
                var request = post(BASE + "/{id}/" + (bothApprove ? "approve" : "reject"), pendingId)
                        .with(actor(anotherManagerId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(bothApprove ? approval("ASSISTANT_MANAGER", storeId) : "{\"reason\":\"경쟁 거절\"}");
                return mvc.perform(request).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(List.of(approve.get(15, TimeUnit.SECONDS), other.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        User saved = users.findById(pendingId).orElseThrow();
        assertThat(saved.getStatus()).isIn(UserStatus.ACTIVE, UserStatus.REJECTED);
        if (saved.getStatus() == UserStatus.ACTIVE) {
            assertThat(saved.getApprovedBy()).isIn(managerId, anotherManagerId);
            assertThat(saved.getApprovedAt()).isEqualTo(NOW);
            assertThat(saved.getRejectionReason()).isNull();
        } else {
            assertThat(saved.getApprovedBy()).isNull();
            assertThat(saved.getRejectionReason()).isEqualTo("경쟁 거절");
        }
    }

    @Test
    void approvalWaitsForStoreDeactivationAndRejectsItsCommittedInactiveState() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Store locked = stores.findByIdForUpdate(storeId).orElseThrow();
            locked.deactivate(NOW);
            stores.flush();
            try (var executor = Executors.newSingleThreadExecutor()) {
                // MySQL에서 실제 Store 잠금 대기를 검사한다. 이 트랜잭션의 롤백 후 재시도도 검증한다.
                var result = executor.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                    jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
                    try {
                        return reviews.approve(managerId, pendingId, new ApproveUserRequest(UserRole.STAFF, storeId));
                    } finally {
                        jdbc.execute("SET SESSION innodb_lock_wait_timeout = 50");
                    }
                }));
                assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(org.springframework.dao.PessimisticLockingFailureException.class);
            }
        });
        assertThatThrownBy(() -> reviews.approve(managerId, pendingId, new ApproveUserRequest(UserRole.STAFF, storeId)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.STORE_INACTIVE));
        assertPendingUnchanged();
    }

    @Test
    void v4UpgradePreservesExistingUsersAndBootstrapRecord() throws Exception {
        String schema = "review_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
                var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/" + schema);
        var v3 = Flyway.configure().dataSource(url, "root", mysql.getPassword())
                .locations("classpath:db/migration").target("3").cleanDisabled(true).load();
        v3.migrate();
        try (var connection = DriverManager.getConnection(url, "root", mysql.getPassword());
                var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO users (email,password_hash,name,role,status,created_at,updated_at) VALUES "
                    + "('preserve@example.com','preserved-hash','보존 총괄','MANAGER','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.executeUpdate("UPDATE manager_bootstrap SET user_id=1, completed_at=UTC_TIMESTAMP(6) WHERE id=1");
        }
        var upgraded = Flyway.configure().dataSource(url, "root", mysql.getPassword())
                .locations("classpath:db/migration").cleanDisabled(true).load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
        upgraded.validate();
        try (var connection = DriverManager.getConnection(url, "root", mysql.getPassword());
                var sql = connection.createStatement();
                var rows = sql.executeQuery("SELECT u.password_hash,u.status,u.rejection_reason,b.user_id "
                        + "FROM users u JOIN manager_bootstrap b ON b.user_id=u.id")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("preserved-hash");
            assertThat(rows.getString(2)).isEqualTo("ACTIVE");
            assertThat(rows.getString(3)).isNull();
            assertThat(rows.getLong(4)).isEqualTo(1L);
        }
    }

    private void assertPendingUnchanged() {
        User user = users.findById(pendingId).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(user.getRole()).isEqualTo(UserRole.STAFF);
        assertThat(user.getHomeStoreId()).isNull();
        assertThat(user.getApprovedBy()).isNull();
        assertThat(user.getApprovedAt()).isNull();
        assertThat(user.getRejectionReason()).isNull();
        assertThat(user.getUpdatedAt()).isEqualTo(CREATED);
    }

    private Long pending(String email) {
        return users.saveAndFlush(User.createPending(email, HASH, "대기 직원", CREATED)).getId();
    }

    private String approval(String role, Long homeStoreId) {
        return "{\"role\":\"" + role + "\",\"homeStoreId\":" + homeStoreId + "}";
    }

    private RequestPostProcessor actor(Long id) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(new UserPrincipal(id), null, List.of()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean @Primary
        Clock reviewClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
