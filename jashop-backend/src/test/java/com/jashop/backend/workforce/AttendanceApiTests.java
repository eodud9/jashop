package com.jashop.backend.workforce;

import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import com.jashop.backend.auth.session.application.AuthSessionService;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.store.domain.Store;
import com.jashop.backend.store.infrastructure.StoreRepository;
import com.jashop.backend.support.JwtTestSupport;
import com.jashop.backend.user.domain.*;
import com.jashop.backend.user.infrastructure.UserRepository;
import com.jashop.backend.workforce.api.dto.*;
import com.jashop.backend.workforce.application.*;
import com.jashop.backend.workforce.infrastructure.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
@Import(AttendanceApiTests.TimeConfig.class)
@Testcontainers
class AttendanceApiTests extends JwtTestSupport {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4").withCommand("--log-bin-trust-function-creators=1");
    static final Instant NOW = Instant.parse("2026-11-14T00:50:00.123456Z");
    static final String BASE = "/api/v1/attendances/me";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired StoreRepository stores;
    @Autowired AttendanceRepository attendances;
    @Autowired WorkScheduleRepository schedules;
    @Autowired WorkScheduleService work;
    @Autowired AttendanceService attendanceService;
    @Autowired AuthSessionService sessions;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JsonMapper json;
    Long manager, staff, other, store, schedule;

    @BeforeEach
    void prepareIsolatedDatabase() {
        clock.set(NOW);
        jdbc.update("DELETE FROM attendance_checkout_requests");
        jdbc.update("DELETE FROM attendances");
        schedules.deleteAll();
        jdbc.update("UPDATE auth_sessions SET replaced_by_id=NULL");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE users SET approved_by=NULL");
        users.deleteAll();
        stores.deleteAll();
        store = stores.saveAndFlush(Store.create("ATTENDANCE", "출퇴근 매장", NOW)).getId();
        manager = users.saveAndFlush(User.createInitialManager("manager@example.com", "test-only-hash", "매니저", NOW)).getId();
        staff = employee("staff");
        other = employee("other");
        schedule = schedule(staff, "2026-11-14T10:00:00+09:00");
    }

    @ParameterizedTest
    @CsvSource({"2026-11-14T00:50:00.123456Z,2026-11-14T01:00:00Z", "2026-11-14T01:00:00Z,2026-11-14T01:00:00Z",
            "2026-11-14T01:15:00.123456Z,2026-11-14T01:15:00.123456Z"})
    void earlyOnTimeAndLatePreserveActualAndComputeRecognizedTime(String actual, String recognized) throws Exception {
        clock.set(Instant.parse(actual));
        var response = call(in(schedule), staff).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.checkInAt").value(actual))
                .andExpect(jsonPath("$.recordedCheckInAt").value(recognized))
                .andExpect(jsonPath("$.checkOutAt").isEmpty()).andExpect(jsonPath("$.approvalStatus").value("PENDING"))
                .andReturn();
        Long id = responseId(response);
        assertThat(jdbc.queryForObject("SELECT check_in_at FROM attendances WHERE id=?", LocalDateTime.class, id))
                .isEqualTo(LocalDateTime.ofInstant(Instant.parse(actual), ZoneOffset.UTC));
        assertThat(jdbc.queryForObject("SELECT recorded_check_in_at FROM attendances WHERE id=?", LocalDateTime.class, id))
                .isEqualTo(LocalDateTime.ofInstant(Instant.parse(recognized), ZoneOffset.UTC));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-13T14:59:59Z", "2026-11-14T15:00:00Z"})
    void checkInIsOnlyAllowedOnSeoulWorkDate(String instant) throws Exception {
        clock.set(Instant.parse(instant));
        call(in(schedule), staff).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTENDANCE_CHECK_IN_DATE"));
        assertThat(attendances.count()).isZero();
    }

    @Test
    void midnightSeoulIsAllowedEvenThoughUtcDateIsPreviousDay() throws Exception {
        clock.set(Instant.parse("2026-11-13T15:00:00Z"));
        call(in(schedule), staff).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkInAt").value("2026-11-13T15:00:00Z"))
                .andExpect(jsonPath("$.recordedCheckInAt").value("2026-11-14T01:00:00Z"));
    }

    @Test
    void onlyOwnerCanCheckInEvenManagerCannotCheckInForAnotherEmployee() throws Exception {
        for (Long actor : List.of(other, manager)) call(in(schedule), actor).andExpect(status().isForbidden());
        assertThat(attendances.count()).isZero();
    }

    @Test
    void cancelledScheduleAndInactiveStoreCannotBeCheckedIn() throws Exception {
        work.cancel(manager, schedule);
        call(in(schedule), staff).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_SCHEDULE_CONFLICT"));
        schedule = schedule(staff, "2026-11-14T10:00:00+09:00");
        jdbc.update("UPDATE stores SET active=false WHERE id=?", store);
        call(in(schedule), staff).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORE_INACTIVE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED", "INACTIVE"})
    void nonActiveAccountCannotCheckInOrReplayCheckout(String state) throws Exception {
        var checked = attendanceService.checkIn(staff, new CheckInRequest(schedule));
        attendanceService.checkOut(staff, checked.id(), "K1");
        String token = token(staff);
        jdbc.update("UPDATE users SET status=? WHERE id=?", state, staff);
        mvc.perform(out(checked.id(), "K1").header("Authorization", token)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_" + state));
        mvc.perform(in(schedule).header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test
    void duplicateCheckInAndRecheckInAfterCheckoutAreRejected() throws Exception {
        Long id = checkedIn();
        call(in(schedule), staff).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_CHECKED_IN"));
        call(out(id, "K1"), staff).andExpect(status().isOk());
        call(in(schedule), staff).andExpect(status().isConflict());
        assertThat(attendances.count()).isEqualTo(1);
    }

    @Test
    void previousActiveAttendanceBlocksCheckInOnNextDay() throws Exception {
        checkedIn();
        clock.set(NOW.plusSeconds(86400));
        Long next = schedule(staff, "2026-11-15T10:00:00+09:00");
        call(in(next), staff).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_CHECKED_IN"));
    }

    @Test
    void checkoutStoresActualServerTimeAndLeavesRecordedCheckoutUnapproved() throws Exception {
        Long id = checkedIn();
        clock.set(Instant.parse("2026-11-14T09:12:00.654321Z"));
        call(out(id, "K1"), staff).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.checkInAt").value(NOW.toString()))
                .andExpect(jsonPath("$.recordedCheckInAt").value("2026-11-14T01:00:00Z"))
                .andExpect(jsonPath("$.checkOutAt").value("2026-11-14T09:12:00.654321Z"))
                .andExpect(jsonPath("$.recordedCheckOutAt").isEmpty()).andExpect(jsonPath("$.approvalStatus").value("PENDING"));
        assertThat(requests()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT created_at FROM attendance_checkout_requests", LocalDateTime.class))
                .isEqualTo(LocalDateTime.parse("2026-11-14T09:12:00.654321"));
    }

    @Test
    void sameKeyAndTargetReturnStoredResponseWithoutRewritingTime() throws Exception {
        Long id = checkedIn();
        var first = call(out(id, "K1"), staff).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        clock.set(NOW.plusSeconds(100));
        // 후속 승인으로 DB 응답 필드가 바뀌어도 기존 키는 최초 응답을 재생한다.
        jdbc.update("UPDATE attendances SET approval_status='APPROVED',recorded_check_out_at=check_out_at,approved_by=?,approved_at=check_out_at WHERE id=?", manager, id);
        call(out(id, "K1"), staff).andExpect(status().isOk()).andExpect(content().json(first));
        assertThat(requests()).isEqualTo(1);
        assertThat(attendances.findById(id).orElseThrow().getCheckOutAt()).isEqualTo(NOW);
    }

    @Test
    void reusedKeyCannotBeRetargetedToNewAttendance() throws Exception {
        Long old = checkedIn();
        call(out(old, "K1"), staff).andExpect(status().isOk());
        Long next = nextDayAttendance();
        call(out(next, "K1"), staff).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        assertThat(attendances.findById(next).orElseThrow().getCheckOutAt()).isNull();
    }

    @Test
    void oldTargetWithNewKeyOrPurgedKeyNeverClosesNewAttendance() throws Exception {
        Long old = checkedIn();
        call(out(old, "K1"), staff).andExpect(status().isOk());
        Long next = nextDayAttendance();
        call(out(old, "K2"), staff).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(old));
        jdbc.update("DELETE FROM attendance_checkout_requests WHERE user_id=? AND request_key='K1'", staff);
        call(out(old, "K1"), staff).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(old));
        assertThat(attendances.findById(next).orElseThrow().getCheckOutAt()).isNull();
    }

    @Test
    void keysAreScopedPerUserAndCaseSensitiveAndOtherUsersCannotCheckoutTarget() throws Exception {
        Long mine = checkedIn();
        Long otherSchedule = schedule(other, "2026-11-14T10:00:00+09:00");
        Long theirs = attendanceService.checkIn(other, new CheckInRequest(otherSchedule)).id();
        call(out(mine, "K1"), other).andExpect(status().isForbidden());
        call(out(mine, "K1"), manager).andExpect(status().isForbidden());
        call(out(mine, "K1"), staff).andExpect(status().isOk());
        call(out(theirs, "K1"), other).andExpect(status().isOk());
        call(out(mine, "k1"), staff).andExpect(status().isOk());
        assertThat(requests()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "key with space", "key,duplicate", "한글"})
    void invalidIdempotencyKeysAreRejected(String key) throws Exception {
        Long id = checkedIn();
        call(out(id, key), staff).andExpect(status().isBadRequest());
        assertThat(requests()).isZero();
        assertThat(attendances.findById(id).orElseThrow().getCheckOutAt()).isNull();
    }

    @Test
    void missingInvalidHeadersAndUnexpectedBodyAreRejectedWithoutFallback() throws Exception {
        Long id = checkedIn();
        call(post(BASE + "/check-out").header("X-Attendance-Id", id), staff).andExpect(status().isBadRequest());
        call(post(BASE + "/check-out").header("Idempotency-Key", "K1").param("attendanceId", id.toString()), staff).andExpect(status().isBadRequest());
        call(out(0L, "K1"), staff).andExpect(status().isBadRequest());
        call(out(id, "a".repeat(129)), staff).andExpect(status().isBadRequest());
        call(out(id, "K1").contentType(APPLICATION_JSON).content("{}"), staff).andExpect(status().isBadRequest());
        call(out(999999L, "K1"), staff).andExpect(status().isNotFound());
        assertThat(attendances.findById(id).orElseThrow().getCheckOutAt()).isNull();
    }

    @Test
    void noBearerCookieBasicAndRevokedSessionCannotAuthenticate() throws Exception {
        Long id = checkedIn();
        mvc.perform(out(id, "K1")).andExpect(status().isUnauthorized());
        mvc.perform(out(id, "K1").header("Authorization", "Basic dGVzdDp0ZXN0")).andExpect(status().isUnauthorized());
        var login = sessions.open(staff);
        mvc.perform(out(id, "K1").cookie(new jakarta.servlet.http.Cookie("access_token", login.response().accessToken())))
                .andExpect(status().isUnauthorized());
        sessions.logout(login.refreshToken());
        mvc.perform(out(id, "K1").header("Authorization", "Bearer " + login.response().accessToken())).andExpect(status().isUnauthorized());
    }

    @Test
    void forgedCheckInTimeOrIdentityIsRejected() throws Exception {
        for (String field : List.of("checkInAt", "recordedCheckInAt", "userId")) {
            call(post(BASE + "/check-in").contentType(APPLICATION_JSON)
                    .content("{\"workScheduleId\":" + schedule + ",\"" + field + "\":\"fake\"}"), staff)
                    .andExpect(status().isBadRequest());
        }
        call(post(BASE + "/check-in").contentType(APPLICATION_JSON).content("{}"), staff).andExpect(status().isBadRequest());
    }

    @Test
    void currentIsOwnActiveAttendanceAndEmptyAfterCheckout() throws Exception {
        call(get(BASE + "/current"), staff).andExpect(status().isNoContent());
        Long id = checkedIn();
        call(get(BASE + "/current"), staff).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        call(get(BASE + "/current").param("userId", staff.toString()), other).andExpect(status().isNoContent());
        call(out(id, "K1"), staff).andExpect(status().isOk());
        call(get(BASE + "/current"), staff).andExpect(status().isNoContent());
    }

    @Test
    void checkInBlocksScheduleChangeAndCancelEvenAfterCheckout() throws Exception {
        Long id = checkedIn();
        for (boolean closed : List.of(false, true)) {
            if (closed) attendanceService.checkOut(staff, id, "K1");
            assertThatThrownBy(() -> work.cancel(manager, schedule)).isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getErrorCode().name()).isEqualTo("WORK_SCHEDULE_CHECKED_IN"));
            assertThatThrownBy(() -> work.update(manager, schedule, new UpdateWorkScheduleRequest(store,
                    OffsetDateTime.parse("2026-11-14T11:00:00+09:00"), null))).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void concurrentCheckInsHaveOneWinner() throws Exception {
        assertThat(race(() -> attendanceService.checkIn(staff, new CheckInRequest(schedule)),
                () -> attendanceService.checkIn(staff, new CheckInRequest(schedule))))
                .containsExactlyInAnyOrder("OK", "ALREADY_CHECKED_IN");
        assertThat(attendances.count()).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyCheckoutReturnsSameResultAndOneLedgerRow() throws Exception {
        Long id = checkedIn();
        assertThat(race(() -> attendanceService.checkOut(staff, id, "K1"), () -> attendanceService.checkOut(staff, id, "K1")))
                .containsExactly("OK", "OK");
        assertThat(requests()).isEqualTo(1);
        assertThat(attendances.findById(id).orElseThrow().getCheckOutAt()).isEqualTo(NOW);
    }

    @Test
    void concurrentDifferentKeysDoNotOverwriteCheckoutTime() throws Exception {
        Long id = checkedIn();
        assertThat(race(() -> attendanceService.checkOut(staff, id, "K1"), () -> attendanceService.checkOut(staff, id, "K2")))
                .containsExactly("OK", "OK");
        assertThat(requests()).isEqualTo(2);
        assertThat(attendances.findById(id).orElseThrow().getCheckOutAt()).isEqualTo(NOW);
    }

    @Test
    void concurrentCancelAndCheckInCannotBothSucceed() throws Exception {
        var results = race(() -> work.cancel(manager, schedule), () -> attendanceService.checkIn(staff, new CheckInRequest(schedule)));
        assertThat(results.stream().filter("OK"::equals)).hasSize(1);
        assertThat(results).anyMatch(value -> value.equals("WORK_SCHEDULE_CHECKED_IN") || value.equals("WORK_SCHEDULE_CONFLICT"));
    }

    @Test
    void ledgerInsertFailureRollsBackCheckoutAndRetryCanSucceed() throws Exception {
        Long id = checkedIn();
        jdbc.execute("CREATE TRIGGER fail_checkout_request BEFORE INSERT ON attendance_checkout_requests FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test-only failure'");
        try { assertThatThrownBy(() -> attendanceService.checkOut(staff, id, "K1")).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("DROP TRIGGER fail_checkout_request"); }
        assertThat(attendances.findById(id).orElseThrow().getCheckOutAt()).isNull();
        assertThat(requests()).isZero();
        call(out(id, "K1"), staff).andExpect(status().isOk());
    }

    @Test
    void multipleActiveRecordsAreRejectedWithoutChoosingOne() throws Exception {
        Long id = checkedIn();
        Long next = schedule(staff, "2026-11-15T10:00:00+09:00");
        jdbc.update("INSERT INTO attendances(work_schedule_id,check_in_at,recorded_check_in_at,created_at) VALUES (?,?,?,?)",
                next, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        call(get(BASE + "/current"), staff).andExpect(status().isConflict());
        call(out(id, "K1"), staff).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTENDANCE_INVALID_STATE"));
        assertThat(requests()).isZero();
    }

    @Test
    void clockCannotMoveCheckoutBeforeActualCheckInAndNoPartialLedgerIsSaved() throws Exception {
        Long id = checkedIn();
        clock.set(NOW.minusSeconds(1));
        call(out(id, "K1"), staff).andExpect(status().isConflict());
        assertThat(requests()).isZero();
    }

    @Test
    void allowedCorsIncludesBothCustomHeaders() throws Exception {
        mvc.perform(options(BASE + "/check-out").header("Origin", AUTH_ORIGIN)
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,idempotency-key,x-attendance-id"))
                .andExpect(status().isOk()).andExpect(header().exists("Access-Control-Allow-Headers"));
    }

    @Test
    void v6MigrationBackfillsEarlyAndLateWithoutChangingActualTimeAndReexecutes() throws Exception {
        String schema = "attendance_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword()); var sql = c.createStatement()) {
            sql.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/" + schema);
        Flyway.configure().dataSource(url, "root", mysql.getPassword()).locations("classpath:db/migration").target("6").load().migrate();
        try (var c = DriverManager.getConnection(url, "root", mysql.getPassword()); var sql = c.createStatement()) {
            sql.executeUpdate("INSERT INTO users(id,email,password_hash,name,role,status,created_at,updated_at) VALUES (1,'preserve@example.com','preserve-hash','보존','MANAGER','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO stores(id,code,name,created_at,updated_at) VALUES (1,'PRESERVE','보존',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO work_schedules(id,user_id,store_id,work_date,start_at,status,created_by,updated_by,created_at,updated_at) VALUES (1,1,1,'2026-11-14','2026-11-14 01:00:00','SCHEDULED',1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),(2,1,1,'2026-11-15','2026-11-15 01:00:00','SCHEDULED',1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO attendances(id,work_schedule_id,check_in_at,check_out_at,created_at) VALUES (1,1,'2026-11-14 00:50:00','2026-11-14 09:12:00',UTC_TIMESTAMP(6)),(2,2,'2026-11-15 01:15:00',NULL,UTC_TIMESTAMP(6))");
        }
        var flyway = Flyway.configure().dataSource(url, "root", mysql.getPassword()).locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        try (var c = DriverManager.getConnection(url, "root", mysql.getPassword()); var sql = c.createStatement();
                var rows = sql.executeQuery("SELECT check_in_at,recorded_check_in_at,check_out_at FROM attendances ORDER BY id")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).startsWith("2026-11-14 00:50:00");
            assertThat(rows.getString(2)).startsWith("2026-11-14 01:00:00");
            assertThat(rows.getString(3)).startsWith("2026-11-14 09:12:00");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).startsWith("2026-11-15 01:15:00");
            assertThat(rows.getString(2)).startsWith("2026-11-15 01:15:00");
        }
    }

    private Long employee(String name) {
        User user = User.createPending(name + "@example.com", "test-only-hash", name, NOW);
        user.approve(UserRole.STAFF, store, manager, NOW);
        return users.saveAndFlush(user).getId();
    }
    private Long schedule(Long employee, String start) {
        return work.create(manager, new CreateWorkScheduleRequest(employee, store, OffsetDateTime.parse(start), null)).id();
    }
    private Long checkedIn() { return attendanceService.checkIn(staff, new CheckInRequest(schedule)).id(); }
    private Long nextDayAttendance() {
        clock.set(NOW.plusSeconds(86400));
        return attendanceService.checkIn(staff, new CheckInRequest(schedule(staff, "2026-11-15T10:00:00+09:00"))).id();
    }
    private String token(Long actor) { return "Bearer " + sessions.open(actor).response().accessToken(); }
    private ResultActions call(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        return mvc.perform(request.header("Authorization", token(actor)));
    }
    private MockHttpServletRequestBuilder in(Long id) {
        return post(BASE + "/check-in").contentType(APPLICATION_JSON).content("{\"workScheduleId\":" + id + "}");
    }
    private MockHttpServletRequestBuilder out(Long id, String key) {
        return post(BASE + "/check-out").header("X-Attendance-Id", id).header("Idempotency-Key", key);
    }
    private Long responseId(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()).get("id").asLong(); }
    private int requests() { return jdbc.queryForObject("SELECT COUNT(*) FROM attendance_checkout_requests", Integer.class); }
    private List<String> race(Runnable first, Runnable second) throws Exception {
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(); return attempt(first); });
            var b = pool.submit(() -> { gate.await(); return attempt(second); });
            gate.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }
    private String attempt(Runnable action) {
        try { action.run(); return "OK"; }
        catch (BusinessException error) { return error.getErrorCode().name(); }
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        void set(Instant value) { now.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean @Primary MutableClock attendanceClock() { return new MutableClock(); }
    }
}
