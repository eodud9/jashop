package com.jashop.backend.workforce;

import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import com.jashop.backend.auth.session.application.AuthSessionService;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.store.domain.Store;
import com.jashop.backend.store.infrastructure.StoreRepository;
import com.jashop.backend.support.JwtTestSupport;
import com.jashop.backend.user.domain.*;
import com.jashop.backend.user.infrastructure.UserRepository;
import com.jashop.backend.workforce.api.dto.*;
import com.jashop.backend.workforce.application.WorkScheduleService;
import com.jashop.backend.workforce.infrastructure.WorkScheduleRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(WorkScheduleApiTests.TimeConfig.class)
@Testcontainers
class WorkScheduleApiTests extends JwtTestSupport {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");
    static final Instant NOW = Instant.parse("2026-11-12T01:00:00.123456Z");
    static final String BASE = "/api/v1/work-schedules";
    static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired StoreRepository stores;
    @Autowired WorkScheduleRepository schedules;
    @Autowired WorkScheduleService service;
    @Autowired AuthSessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    Long manager, staff, otherStaff, assistant, otherAssistant, store, otherStore;
    Map<Long, String> access;

    @BeforeEach
    void prepareIsolatedDatabase() {
        jdbc.update("DELETE FROM attendances");
        schedules.deleteAll();
        jdbc.update("UPDATE auth_sessions SET replaced_by_id=NULL");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE users SET approved_by=NULL");
        users.deleteAll();
        stores.deleteAll();
        store = stores.saveAndFlush(Store.create("SCHEDULE", "일정 매장", NOW)).getId();
        otherStore = stores.saveAndFlush(Store.create("OTHER", "다른 매장", NOW)).getId();
        manager = users.saveAndFlush(User.createInitialManager("manager@example.com", "test-only-hash", "매니저", NOW)).getId();
        staff = employee("staff", UserRole.STAFF, store);
        otherStaff = employee("other", UserRole.STAFF, otherStore);
        assistant = employee("assistant", UserRole.ASSISTANT_MANAGER, store);
        otherAssistant = employee("other-assistant", UserRole.ASSISTANT_MANAGER, otherStore);
        access = new HashMap<>();
        for (Long id : List.of(manager, staff, otherStaff, assistant, otherAssistant)) {
            access.put(id, sessions.open(id).response().accessToken());
        }
    }

    @Test
    void realBearerCreatesNullableEndAndStoresSeoulDateUtcAndAudit() throws Exception {
        var result = call(post(BASE).contentType(APPLICATION_JSON).content(body(staff, store, "2026-11-15T10:00:00+09:00", null)), manager)
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.workDate").value("2026-11-15"))
                .andExpect(jsonPath("$.startAt").value("2026-11-15T01:00:00Z"))
                .andExpect(jsonPath("$.endAt").isEmpty()).andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.createdBy").value(manager)).andExpect(jsonPath("$.updatedBy").value(manager)).andReturn();
        long id = id(result);
        assertThat(jdbc.queryForObject("SELECT start_at FROM work_schedules WHERE id=?", LocalDateTime.class, id))
                .isEqualTo(LocalDateTime.parse("2026-11-15T01:00:00"));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("password", "test-only-hash");
    }

    @Test
    void updateAndCancelPreserveHistoryAndAllowNewScheduleOnSameDate() throws Exception {
        Long id = create(staff, "2026-11-15T09:00:00+09:00", "2026-11-15T19:00:00+09:00").id();
        String changed = body(null, otherStore, "2026-11-15T10:00:00+09:00", null);
        call(patch(BASE + "/" + id).contentType(APPLICATION_JSON).content(changed), manager)
                .andExpect(status().isOk()).andExpect(jsonPath("$.storeId").value(otherStore));
        call(post(BASE + "/" + id + "/cancel"), manager).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value(manager)).andExpect(jsonPath("$.cancelledAt").value(NOW.toString()));
        create(staff, "2026-11-15T09:00:00+09:00", null);
        assertThat(schedules.count()).isEqualTo(2);
        call(post(BASE + "/" + id + "/cancel"), manager).andExpect(status().isConflict());
        call(patch(BASE + "/" + id).contentType(APPLICATION_JSON).content(changed), manager).andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-15T08:59:59+09:00", "2026-11-15T09:00:00+09:00", "2026-11-16T00:00:00+09:00"})
    void invalidEndAndOvernightAreRejected(String end) throws Exception {
        call(post(BASE).contentType(APPLICATION_JSON).content(body(staff, store, "2026-11-15T09:00:00+09:00", end)), manager)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(schedules.count()).isZero();
    }

    @Test
    void offsetNormalizationUsesSeoulDateAndRejectsOvernightEvenWhenUtcDateMatches() throws Exception {
        var first = create(staff, "2026-11-14T15:00:00Z", "2026-11-15T14:59:59Z");
        assertThat(first.workDate()).isEqualTo(LocalDate.parse("2026-11-15"));
        call(post(BASE).contentType(APPLICATION_JSON).content(body(otherStaff, store, "2026-11-15T14:00:00Z", "2026-11-15T16:00:00Z")), manager)
                .andExpect(status().isBadRequest());
    }

    @Test
    void dateRangeIncludesBothSeoulBoundariesAndSortsByStartThenIdWithPagination() throws Exception {
        create(staff, "2026-11-14T23:59:59+09:00", null);
        var first = create(staff, "2026-11-15T00:00:00+09:00", null);
        var second = create(otherStaff, "2026-11-15T00:00:00+09:00", null);
        var last = create(assistant, "2026-11-15T23:59:59+09:00", null);
        create(staff, "2026-11-16T00:00:00+09:00", null);
        call(list("2026-11-15", "2026-11-15").param("size", "2"), manager).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].id").value(first.id())).andExpect(jsonPath("$.content[1].id").value(second.id()));
        call(list("2026-11-15", "2026-11-15").param("size", "2").param("page", "1"), assistant)
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(last.id()));
        call(get(BASE + "/me").param("fromDate", "2026-11-15").param("toDate", "2026-11-15"), staff)
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void cancelledHistoryIsIncludedAndInactiveStoreHistoryRemainsReadable() throws Exception {
        var schedule = create(staff, "2026-11-15T09:00:00+09:00", null);
        service.cancel(manager, schedule.id());
        jdbc.update("UPDATE stores SET active=false WHERE id=?", store);
        call(list("2026-11-15", "2026-11-15"), assistant).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("CANCELLED"));
    }

    @Test
    void onlyOwnerManagerAndScheduleStoreAssistantCanReadDetail() throws Exception {
        Long id = create(otherStaff, "2026-11-15T09:00:00+09:00", null).id(); // 소속과 다른 매장 배정 허용
        for (Long actor : List.of(manager, otherStaff, assistant)) call(get(BASE + "/" + id), actor).andExpect(status().isOk());
        for (Long actor : List.of(staff, otherAssistant)) call(get(BASE + "/" + id), actor).andExpect(status().isForbidden());
        call(list("2026-11-15", "2026-11-15"), otherAssistant).andExpect(status().isForbidden());
        call(list("2026-11-15", "2026-11-15"), staff).andExpect(status().isForbidden());
    }

    @Test
    void employeesCannotCreateModifyOrCancelAndSpoofedActorIsIgnored() throws Exception {
        Long id = create(staff, "2026-11-15T09:00:00+09:00", null).id();
        for (Long actor : List.of(staff, assistant)) {
            call(post(BASE).header("X-User-Id", manager).contentType(APPLICATION_JSON)
                    .content(body(otherStaff, store, "2026-11-15T09:00:00+09:00", null)), actor).andExpect(status().isForbidden());
            call(patch(BASE + "/" + id).contentType(APPLICATION_JSON)
                    .content(body(null, store, "2026-11-15T10:00:00+09:00", null)), actor).andExpect(status().isForbidden());
            call(post(BASE + "/" + id + "/cancel"), actor).andExpect(status().isForbidden());
        }
    }

    @Test
    void bearerIsRequiredAndCookieQueryOrBasicDoNotAuthenticate() throws Exception {
        mvc.perform(get(BASE + "/me").param("fromDate", "2026-11-15").param("toDate", "2026-11-15")
                .param("access_token", access.get(staff)).cookie(new jakarta.servlet.http.Cookie("access_token", access.get(staff))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(body(staff, store, "2026-11-15T09:00:00+09:00", null)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/1").header("Authorization", "Basic dGVzdDp0ZXN0")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/1").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED", "INACTIVE"})
    void nonActiveActorAndTargetAreRejected(String state) throws Exception {
        jdbc.update("UPDATE users SET status=? WHERE id=?", state, staff);
        call(post(BASE).contentType(APPLICATION_JSON).content(body(staff, store, "2026-11-15T09:00:00+09:00", null)), manager)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_" + state));
        call(get(BASE + "/me").param("fromDate", "2026-11-15").param("toDate", "2026-11-15"), staff)
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidIdsMissingResourcesAndInactiveStoreAreRejected() throws Exception {
        call(get(BASE + "/0"), manager).andExpect(status().isBadRequest());
        call(get(BASE + "/999999"), manager).andExpect(status().isNotFound());
        call(post(BASE).contentType(APPLICATION_JSON).content(body(999999L, store, "2026-11-15T09:00:00+09:00", null)), manager)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        call(post(BASE).contentType(APPLICATION_JSON).content(body(staff, 999999L, "2026-11-15T09:00:00+09:00", null)), manager)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        jdbc.update("UPDATE stores SET active=false WHERE id=?", store);
        call(post(BASE).contentType(APPLICATION_JSON).content(body(staff, store, "2026-11-15T09:00:00+09:00", null)), manager)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORE_INACTIVE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=2147483647", "fromDate=bad", "toDate=2026-11-14"})
    void invalidListParametersAreRejected(String parameter) throws Exception {
        String[] pair = parameter.split("=");
        var request = get(BASE).param("storeId", store.toString())
                .param("fromDate", pair[0].equals("fromDate") ? pair[1] : "2026-11-15")
                .param("toDate", pair[0].equals("toDate") ? pair[1] : "2026-11-15");
        if (!pair[0].equals("fromDate") && !pair[0].equals("toDate")) request.param(pair[0], pair[1]);
        call(request, manager).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"userId\":0,\"storeId\":1,\"startAt\":\"2026-11-15T09:00:00+09:00\"}",
            "{\"userId\":1,\"storeId\":1,\"startAt\":\"2026-11-15T09:00:00\"}",
            "{\"userId\":1,\"storeId\":1,\"startAt\":\"2026-11-15T09:00:00+09:00\",\"createdBy\":1}"})
    void malformedAndForgedBodiesAreRejected(String body) throws Exception {
        call(post(BASE).contentType(APPLICATION_JSON).content(body), manager).andExpect(status().isBadRequest());
    }

    @Test
    void checkedInAndAlreadyCheckedOutSchedulesCannotBeChangedOrCancelled() throws Exception {
        Long id = create(staff, "2026-11-15T09:00:00+09:00", null).id();
        jdbc.update("INSERT INTO attendances(work_schedule_id,check_in_at,created_at) VALUES (?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", id);
        for (boolean checkedOut : List.of(false, true)) {
            if (checkedOut) jdbc.update("UPDATE attendances SET check_out_at=check_in_at WHERE work_schedule_id=?", id);
            call(patch(BASE + "/" + id).contentType(APPLICATION_JSON)
                    .content(body(null, otherStore, "2026-11-15T10:00:00+09:00", null)), manager)
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_SCHEDULE_CHECKED_IN"));
            call(post(BASE + "/" + id + "/cancel"), manager).andExpect(status().isConflict());
        }
        assertThat(schedules.findById(id).orElseThrow().getStoreId()).isEqualTo(store);
    }

    @Test
    void changingDateToOccupiedDayRollsBackTimeStoreAndAudit() throws Exception {
        create(staff, "2026-11-15T09:00:00+09:00", null);
        var original = create(staff, "2026-11-16T09:00:00+09:00", null);
        call(patch(BASE + "/" + original.id()).contentType(APPLICATION_JSON)
                .content(body(null, otherStore, "2026-11-15T10:00:00+09:00", null)), manager)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_SCHEDULE_CONFLICT"));
        assertThat(service.get(manager, original.id())).isEqualTo(original);
    }

    @Test
    void concurrentCreatesForSameEmployeeDayAcrossStoresHaveExactlyOneWinner() throws Exception {
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(); return attempt(() -> service.create(manager, request(staff, store))); });
            var b = pool.submit(() -> { gate.await(); return attempt(() -> service.create(manager, request(staff, otherStore))); });
            gate.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "WORK_SCHEDULE_CONFLICT");
        }
        assertThat(schedules.count()).isEqualTo(1);
    }

    @Test
    void concurrentUpdateAndCancelReadCurrentStateAfterWaiting() throws Exception {
        var schedule = create(staff, "2026-11-15T09:00:00+09:00", null);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                users.findByIdForUpdate(staff).orElseThrow();
                var row = schedules.findByIdForUpdate(schedule.id()).orElseThrow();
                row.cancel(manager, NOW);
                locked.countDown();
                await(release);
                return "OK";
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var started = new CountDownLatch(1);
            var second = pool.submit(() -> { started.countDown(); return attempt(() -> service.update(manager, schedule.id(),
                    new UpdateWorkScheduleRequest(store, OffsetDateTime.parse("2026-11-15T10:00:00+09:00"), null))); });
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("OK");
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("WORK_SCHEDULE_CONFLICT");
        } finally { release.countDown(); }
    }

    @Test
    void checkInCommittedDuringLockWaitPreventsFollowingCancel() throws Exception {
        var schedule = create(staff, "2026-11-15T09:00:00+09:00", null);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var checkIn = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                users.findByIdForUpdate(staff).orElseThrow();
                schedules.findByIdForUpdate(schedule.id()).orElseThrow();
                jdbc.update("INSERT INTO attendances(work_schedule_id,check_in_at,created_at) VALUES (?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", schedule.id());
                locked.countDown();
                await(release);
                return "OK";
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var cancel = pool.submit(() -> attempt(() -> service.cancel(manager, schedule.id())));
            try { assertThatThrownBy(() -> cancel.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            assertThat(checkIn.get(10, TimeUnit.SECONDS)).isEqualTo("OK");
            assertThat(cancel.get(10, TimeUnit.SECONDS)).isEqualTo("WORK_SCHEDULE_CHECKED_IN");
        } finally { release.countDown(); }
    }

    @Test
    void databaseEnforcesUniqueActiveDayNightAndForeignKeysIndependently() {
        Long id = create(staff, "2026-11-15T09:00:00+09:00", null).id();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO work_schedules(user_id,store_id,work_date,start_at,status,created_by,updated_by,created_at,updated_at) "
                + "SELECT user_id,store_id,work_date,start_at,status,created_by,updated_by,created_at,updated_at FROM work_schedules WHERE id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE work_schedules SET end_at='2026-11-15 15:00:00' WHERE id=?", id))
                .isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE work_schedules SET work_date='2026-11-16' WHERE id=?", id))
                .isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE work_schedules SET store_id=999999 WHERE id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void v5UpgradePreservesUserStoreAndSessionAndReexecutionPreservesSchedules() throws Exception {
        String schema = "work_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword()); var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/" + schema);
        Flyway.configure().dataSource(url, "root", mysql.getPassword()).locations("classpath:db/migration").target("5").load().migrate();
        try (var connection = DriverManager.getConnection(url, "root", mysql.getPassword()); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO users(id,email,password_hash,name,role,status,created_at,updated_at) VALUES (1,'preserve@example.com','preserve-hash','보존','MANAGER','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO stores(id,code,name,created_at,updated_at) VALUES (1,'PRESERVE','보존',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO auth_sessions(user_id,family_id,token_hash,issued_at,expires_at,created_at) VALUES (1,'00000000-0000-0000-0000-000000000001',REPEAT('a',64),'2026-11-12 00:00:00','2026-11-19 00:00:00',UTC_TIMESTAMP(6))");
            sql.executeUpdate("UPDATE manager_bootstrap SET user_id=1,completed_at=UTC_TIMESTAMP(6) WHERE id=1");
        }
        var flyway = Flyway.configure().dataSource(url, "root", mysql.getPassword()).locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(url, "root", mysql.getPassword()); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO work_schedules(user_id,store_id,work_date,start_at,status,created_by,updated_by,created_at,updated_at) VALUES (1,1,'2026-11-15','2026-11-15 01:00:00','SCHEDULED',1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            flyway.validate();
            try (var rows = sql.executeQuery("SELECT u.password_hash,(SELECT COUNT(*) FROM auth_sessions),(SELECT COUNT(*) FROM work_schedules),b.user_id FROM users u JOIN manager_bootstrap b ON b.user_id=u.id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("preserve-hash");
                assertThat(rows.getInt(2)).isEqualTo(1);
                assertThat(rows.getInt(3)).isEqualTo(1);
                assertThat(rows.getLong(4)).isEqualTo(1);
            }
        }
    }

    private Long employee(String name, UserRole role, Long homeStore) {
        User user = User.createPending(name + "@example.com", "test-only-hash", name, NOW);
        user.approve(role, homeStore, manager, NOW);
        return users.saveAndFlush(user).getId();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + access.get(actor)));
    }

    private MockHttpServletRequestBuilder list(String from, String to) {
        return get(BASE).param("storeId", store.toString()).param("fromDate", from).param("toDate", to);
    }

    private String body(Long employee, Long location, String start, String end) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (employee != null) fields.put("userId", employee);
        fields.put("storeId", location);
        fields.put("startAt", start);
        fields.put("endAt", end);
        return JSON.writeValueAsString(fields);
    }

    private CreateWorkScheduleRequest request(Long employee, Long location) {
        return new CreateWorkScheduleRequest(employee, location, OffsetDateTime.parse("2026-11-15T09:00:00+09:00"), null);
    }

    private WorkScheduleResponse create(Long employee, String start, String end) {
        return service.create(manager, new CreateWorkScheduleRequest(employee, store, OffsetDateTime.parse(start),
                end == null ? null : OffsetDateTime.parse(end)));
    }

    private long id(MvcResult result) throws Exception { return JSON.readTree(result.getResponse().getContentAsString()).get("id").asLong(); }

    private String attempt(Runnable command) {
        try { command.run(); return "OK"; }
        catch (BusinessException exception) { return exception.getErrorCode().name(); }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("테스트 잠금 해제 시간 초과"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean @Primary Clock workScheduleClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
