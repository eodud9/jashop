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
import org.junit.jupiter.params.provider.ValueSource;
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
@Import(CheckoutNoticeApiTests.TimeConfig.class)
@Testcontainers
class CheckoutNoticeApiTests extends JwtTestSupport {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4").withCommand("--log-bin-trust-function-creators=1");
    static final String BASE = "/api/v1/checkout-notices";
    static final Instant START = Instant.parse("2026-11-19T00:50:00.123456Z");
    static final Instant NOW = Instant.parse("2026-11-19T08:40:00.123456Z");
    static final String PLANNED = "2026-11-19T18:00:00+09:00";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired StoreRepository stores;
    @Autowired WorkScheduleRepository schedules;
    @Autowired CheckoutNoticeRepository notices;
    @Autowired WorkScheduleService work;
    @Autowired AttendanceService attendance;
    @Autowired AttendanceManagementService management;
    @Autowired CheckoutNoticeService service;
    @Autowired AuthSessionService sessions;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MutableClock clock;
    @Autowired JsonMapper json;
    Long manager, assistant, otherAssistant, staff, second, store, otherStore, schedule, secondSchedule, attendanceId;

    @BeforeEach
    void prepareIsolatedDatabase() {
        clock.set(START);
        notices.deleteAll();
        jdbc.update("DELETE FROM attendance_checkout_requests");
        jdbc.update("DELETE FROM attendances");
        schedules.deleteAll();
        jdbc.update("UPDATE auth_sessions SET replaced_by_id=NULL");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE users SET approved_by=NULL");
        users.deleteAll();
        stores.deleteAll();
        store = stores.saveAndFlush(Store.create("NOTICE", "안내 매장", START)).getId();
        otherStore = stores.saveAndFlush(Store.create("OTHER", "타 매장", START)).getId();
        manager = users.saveAndFlush(User.createInitialManager("manager@example.com", "test-hash", "총괄", START)).getId();
        assistant = employee("assistant", UserRole.ASSISTANT_MANAGER, store);
        otherAssistant = employee("other-assistant", UserRole.ASSISTANT_MANAGER, otherStore);
        staff = employee("staff", UserRole.STAFF, store);
        second = employee("transfer", UserRole.STAFF, otherStore);
        schedule = schedule(staff, store, "2026-11-19");
        secondSchedule = schedule(second, store, "2026-11-19");
        attendanceId = attendance.checkIn(staff, new CheckInRequest(schedule)).id();
        attendance.checkIn(second, new CheckInRequest(secondSchedule));
        clock.set(NOW);
    }

    @Test
    void batchCreatesPerEmployeeNoticeInInputOrderWithUtcAndSenderWithoutChangingOtherRecords() throws Exception {
        var beforeSchedule = work.get(manager, schedule);
        var beforeAttendance = attendance.current(staff);
        call(create(List.of(secondSchedule, schedule), PLANNED), assistant).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.notices.length()").value(2))
                .andExpect(jsonPath("$.notices[0].workScheduleId").value(secondSchedule))
                .andExpect(jsonPath("$.notices[1].userId").value(staff))
                .andExpect(jsonPath("$.notices[1].storeId").value(store))
                .andExpect(jsonPath("$.notices[1].plannedCheckOutAt").value("2026-11-19T09:00:00Z"))
                .andExpect(jsonPath("$.notices[1].sentBy").value(assistant))
                .andExpect(jsonPath("$.notices[1].sentAt").value(NOW.toString()))
                .andExpect(jsonPath("$.notices[1].acknowledgedAt").isEmpty());
        assertThat(notices.count()).isEqualTo(2);
        assertThat(work.get(manager, schedule)).isEqualTo(beforeSchedule);
        assertThat(attendance.current(staff)).isEqualTo(beforeAttendance);
        assertThat(jdbc.queryForObject("SELECT planned_check_out_at FROM checkout_notices ORDER BY id LIMIT 1", LocalDateTime.class))
                .isEqualTo(LocalDateTime.parse("2026-11-19T09:00:00"));
    }

    @Test
    void reannouncementCreatesNewHistoryAndAcknowledgementsAreIndependent() throws Exception {
        Long first = send(schedule, PLANNED);
        call(post(BASE + "/" + first + "/acknowledge"), staff).andExpect(status().isOk());
        clock.set(NOW.plusSeconds(60));
        Long next = send(schedule, "2026-11-19T18:30:00+09:00");
        assertThat(next).isNotEqualTo(first);
        assertThat(notices.findById(first).orElseThrow().getPlannedCheckOutAt()).isEqualTo(Instant.parse("2026-11-19T09:00:00Z"));
        assertThat(notices.findById(first).orElseThrow().getAcknowledgedAt()).isEqualTo(NOW);
        assertThat(notices.findById(next).orElseThrow().getAcknowledgedAt()).isNull();
        assertThat(notices.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-19T18:15:00+09:00", "2026-11-19T18:00:01+09:00", "2026-11-19T18:00:00.000001+09:00"})
    void onlyExactHalfHoursAreAllowed(String time) throws Exception {
        call(create(List.of(schedule), time), assistant).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NOTICE_TIME_UNIT"));
        assertThat(notices.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-19T17:30:00+09:00", "2026-11-20T00:00:00+09:00"})
    void pastAndDifferentWorkDateAreRejected(String time) throws Exception {
        call(create(List.of(schedule), time), assistant).andExpect(status().isBadRequest());
        assertThat(notices.count()).isZero();
    }

    @Test
    void exactCurrentTimeIsRejectedButNextHalfHourAndUtcOffsetInputAreAllowed() throws Exception {
        clock.set(Instant.parse("2026-11-19T09:00:00Z"));
        call(create(List.of(schedule), PLANNED), assistant).andExpect(status().isBadRequest());
        call(create(List.of(schedule), "2026-11-19T09:30:00Z"), assistant).andExpect(status().isCreated());
    }

    @Test
    void noCheckInCancelledAndEndedTargetsAreRejectedWithoutPartialBatch() throws Exception {
        Long absent = schedule(assistant, store, "2026-11-19");
        call(create(List.of(schedule, absent), PLANNED), assistant).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECKOUT_NOTICE_TARGET_INVALID"));
        work.cancel(manager, absent);
        call(create(List.of(schedule, absent), PLANNED), assistant).andExpect(status().isConflict());
        attendance.checkOut(staff, attendanceId, "K1");
        call(create(List.of(secondSchedule, schedule), PLANNED), assistant).andExpect(status().isConflict());
        assertThat(notices.count()).isZero();
    }

    @Test
    void operationallyClosedTargetWithMissingCheckoutIsAlsoRejected() throws Exception {
        management.approve(assistant, attendanceId, new ApproveAttendanceRequest(OffsetDateTime.parse("2026-11-19T17:30:00+09:00")));
        call(create(List.of(schedule), PLANNED), assistant).andExpect(status().isConflict());
        assertThat(notices.count()).isZero();
    }

    @Test
    void priorAndFutureDaySchedulesCannotReceiveTodaysAnnouncement() throws Exception {
        clock.set(START.minusSeconds(86400));
        Long prior = schedule(otherAssistant, store, "2026-11-18");
        attendance.checkIn(otherAssistant, new CheckInRequest(prior));
        clock.set(NOW);
        call(create(List.of(prior), PLANNED), assistant).andExpect(status().isBadRequest());
        clock.set(START.plusSeconds(86400));
        Long future = schedule(assistant, store, "2026-11-20");
        attendance.checkIn(assistant, new CheckInRequest(future));
        clock.set(NOW);
        call(create(List.of(future), "2026-11-20T18:00:00+09:00"), assistant).andExpect(status().isConflict());
        assertThat(notices.count()).isZero();
    }

    @Test
    void duplicateEmptyNullAndOversizedIdsAreRejected() throws Exception {
        call(create(List.of(schedule, schedule), PLANNED), assistant).andExpect(status().isBadRequest());
        call(create(List.of(), PLANNED), assistant).andExpect(status().isBadRequest());
        call(create(Arrays.asList(schedule, null), PLANNED), assistant).andExpect(status().isBadRequest());
        call(create(List.of(0L), PLANNED), assistant).andExpect(status().isBadRequest());
        call(create(java.util.stream.LongStream.range(1,102).boxed().toList(), PLANNED), assistant).andExpect(status().isBadRequest());
        assertThat(notices.count()).isZero();
    }

    @Test
    void missingScheduleAndCrossStoreBatchAreRejectedAtomically() throws Exception {
        call(create(List.of(schedule, 999999L), PLANNED), assistant).andExpect(status().isNotFound());
        clock.set(START);
        Long other = schedule(otherAssistant, otherStore, "2026-11-19");
        attendance.checkIn(otherAssistant, new CheckInRequest(other));
        clock.set(NOW);
        call(create(List.of(schedule, other), PLANNED), assistant).andExpect(status().isForbidden());
        assertThat(notices.count()).isZero();
    }

    @Test
    void onlyScheduleStoreAssistantCanSendIncludingToSelf() throws Exception {
        for (Long actor : List.of(manager, staff, otherAssistant)) {
            call(create(List.of(schedule), PLANNED), actor).andExpect(status().isForbidden());
        }
        // 기본 소속이 다른 직원도 실제 일정 매장의 부매니저가 안내한다.
        call(create(List.of(secondSchedule), PLANNED), assistant).andExpect(status().isCreated());
        clock.set(START);
        Long own = schedule(assistant, store, "2026-11-19");
        attendance.checkIn(assistant, new CheckInRequest(own));
        clock.set(NOW);
        call(create(List.of(own), PLANNED), assistant).andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED", "INACTIVE"})
    void nonActiveAccountsCannotSendReadOrAcknowledge(String state) throws Exception {
        Long id = send(schedule, PLANNED);
        String senderToken = token(assistant);
        String receiverToken = token(staff);
        jdbc.update("UPDATE users SET status=? WHERE id IN (?,?)", state, assistant, staff);
        mvc.perform(create(List.of(schedule), PLANNED).header("Authorization", senderToken)).andExpect(status().isForbidden());
        mvc.perform(list().header("Authorization", senderToken)).andExpect(status().isForbidden());
        mvc.perform(mine().header("Authorization", receiverToken)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/" + id + "/acknowledge").header("Authorization", receiverToken)).andExpect(status().isForbidden());
    }

    @Test
    void acknowledgementIsOwnerOnlyAndKeepsFirstServerTime() throws Exception {
        Long id = send(schedule, PLANNED);
        for (Long actor : List.of(manager, assistant, second)) {
            call(post(BASE + "/" + id + "/acknowledge"), actor).andExpect(status().isForbidden());
        }
        clock.set(NOW.plusSeconds(5));
        call(post(BASE + "/" + id + "/acknowledge"), staff).andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedAt").value(NOW.plusSeconds(5).toString()));
        clock.set(NOW.plusSeconds(100));
        call(post(BASE + "/" + id + "/acknowledge"), staff).andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedAt").value(NOW.plusSeconds(5).toString()));
    }

    @Test
    void oldNoticeRemainsReadableAndAcknowledgableAfterOperationalClosureAndDateChange() throws Exception {
        Long id = send(schedule, PLANNED);
        management.approve(assistant, attendanceId, new ApproveAttendanceRequest(OffsetDateTime.parse("2026-11-19T17:30:00+09:00")));
        clock.set(NOW.plusSeconds(86400));
        call(post(BASE + "/" + id + "/acknowledge"), staff).andExpect(status().isOk());
        call(mine(), staff).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void mineAndStoreListsHaveDescendingTimeIdOrderAndPagination() throws Exception {
        Long first = send(schedule, PLANNED);
        Long secondId = send(schedule, "2026-11-19T18:30:00+09:00");
        clock.set(NOW.plusSeconds(60));
        Long third = send(secondSchedule, PLANNED);
        call(list().param("size", "2"), manager).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].id").value(third)).andExpect(jsonPath("$.content[1].id").value(secondId));
        call(list().param("size", "2").param("page", "1"), assistant).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(first));
        call(mine(), staff).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(secondId));
    }

    @Test
    void listsAreScopedToOwnerAndScheduleStoreAndAllowInactiveStoreHistory() throws Exception {
        send(schedule, PLANNED);
        call(mine().param("userId", staff.toString()), second).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        call(list(), otherAssistant).andExpect(status().isForbidden());
        call(list(), staff).andExpect(status().isForbidden());
        jdbc.update("UPDATE stores SET active=false WHERE id=?", store);
        call(list(), assistant).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listPeriodUsesSeoulSentDateIncludingMidnightBoundaries() throws Exception {
        Long id = send(schedule, PLANNED);
        // 생성 API를 우회한 JDBC fixture로 서울 발송일의 조회 경계만 검증한다.
        jdbc.update("UPDATE checkout_notices SET sent_at='2026-11-18 15:00:00' WHERE id=?", id);
        call(mine(), staff).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        call(get(BASE + "/me").param("fromDate", "2026-11-18").param("toDate", "2026-11-18"), staff)
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        jdbc.update("UPDATE checkout_notices SET sent_at='2026-11-18 14:59:59.999999' WHERE id=?", id);
        call(mine(), staff).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=2147483647", "fromDate=bad", "toDate=2026-11-18"})
    void invalidListParametersAreRejected(String parameter) throws Exception {
        var pair = parameter.split("=");
        var query = get(BASE + "/me")
                .param("fromDate", pair[0].equals("fromDate") ? pair[1] : "2026-11-19")
                .param("toDate", pair[0].equals("toDate") ? pair[1] : "2026-11-19");
        if (!pair[0].equals("fromDate") && !pair[0].equals("toDate")) query.param(pair[0], pair[1]);
        call(query, staff).andExpect(status().isBadRequest());
    }

    @Test
    void missingParametersUnknownNoticeAndForgedInputsAreRejected() throws Exception {
        call(get(BASE + "/me"), staff).andExpect(status().isBadRequest());
        call(get(BASE).param("fromDate", "2026-11-19").param("toDate", "2026-11-19"), manager).andExpect(status().isBadRequest());
        call(get(BASE).param("storeId", "999999").param("fromDate", "2026-11-19").param("toDate", "2026-11-19"), manager).andExpect(status().isNotFound());
        call(post(BASE + "/999999/acknowledge"), staff).andExpect(status().isNotFound());
        for (String body : List.of("{}", "{\"workScheduleIds\":[1],\"plannedCheckOutAt\":\"2026-11-19T18:00:00\"}",
                "{\"workScheduleIds\":[1],\"plannedCheckOutAt\":\"2026-11-19T18:00:00+09:00\",\"sentBy\":1}")) {
            call(post(BASE).contentType(APPLICATION_JSON).content(body), assistant).andExpect(status().isBadRequest());
        }
        Long id = send(schedule, PLANNED);
        call(post(BASE + "/" + id + "/acknowledge").contentType(APPLICATION_JSON).content("{\"acknowledgedAt\":\"fake\"}"), staff)
                .andExpect(status().isBadRequest());
    }

    @Test
    void bearerIsRequiredAndRevokedSessionCannotUseNoticeEndpoints() throws Exception {
        mvc.perform(create(List.of(schedule), PLANNED)).andExpect(status().isUnauthorized());
        mvc.perform(mine()).andExpect(status().isUnauthorized());
        mvc.perform(create(List.of(schedule), PLANNED).header("Authorization", "Basic dGVzdDp0ZXN0")).andExpect(status().isUnauthorized());
        var login = sessions.open(assistant);
        sessions.logout(login.refreshToken());
        mvc.perform(create(List.of(schedule), PLANNED).header("Authorization", "Bearer " + login.response().accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentAcknowledgementsKeepOneTimestamp() throws Exception {
        Long id = send(schedule, PLANNED);
        assertThat(race(() -> service.acknowledge(staff, id), () -> service.acknowledge(staff, id))).containsExactly("OK", "OK");
        assertThat(notices.findById(id).orElseThrow().getAcknowledgedAt()).isEqualTo(NOW);
    }

    @Test
    void concurrentBatchRequestsWithDifferentInputOrdersPreserveAllHistories() throws Exception {
        assertThat(race(() -> service.create(assistant, request(List.of(schedule, secondSchedule), PLANNED)),
                () -> service.create(assistant, request(List.of(secondSchedule, schedule), PLANNED))))
                .containsExactly("OK", "OK");
        assertThat(notices.count()).isEqualTo(4);
    }

    @Test
    void assistantsCanConcurrentlyAnnounceToEachOtherWithoutLockInversion() throws Exception {
        Long another = employee("same-store-assistant", UserRole.ASSISTANT_MANAGER, store);
        clock.set(START);
        Long a = schedule(assistant, store, "2026-11-19");
        Long b = schedule(another, store, "2026-11-19");
        attendance.checkIn(assistant, new CheckInRequest(a));
        attendance.checkIn(another, new CheckInRequest(b));
        clock.set(NOW);
        assertThat(race(() -> service.create(assistant, request(List.of(b), PLANNED)),
                () -> service.create(another, request(List.of(a), PLANNED)))).containsExactly("OK", "OK");
    }

    @Test
    void concurrentEmployeeCheckoutAndNoticeUseConsistentEligibility() throws Exception {
        var results = race(() -> service.create(assistant, request(List.of(schedule), PLANNED)),
                () -> attendance.checkOut(staff, attendanceId, "K1"));
        assertThat(results.getLast()).isEqualTo("OK");
        assertThat(results.getFirst()).isIn("OK", "CHECKOUT_NOTICE_TARGET_INVALID");
        assertThat(notices.count()).isEqualTo(results.getFirst().equals("OK") ? 1 : 0);
        assertThat(attendance.current(staff)).isNull();
    }

    @Test
    void concurrentOperationalClosureAndNoticeUseClosedAtNotEmployeeCheckout() throws Exception {
        var results = race(() -> service.create(assistant, request(List.of(schedule), PLANNED)),
                () -> management.approve(assistant, attendanceId, new ApproveAttendanceRequest(OffsetDateTime.parse("2026-11-19T17:30:00+09:00"))));
        assertThat(results.getLast()).isEqualTo("OK");
        assertThat(results.getFirst()).isIn("OK", "CHECKOUT_NOTICE_TARGET_INVALID");
        assertThat(notices.count()).isEqualTo(results.getFirst().equals("OK") ? 1 : 0);
    }

    @Test
    void timeIsRecheckedAfterWaitingForUserLock() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                users.findByIdForUpdate(staff).orElseThrow();
                locked.countDown();
                await(release);
                return "OK";
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var sender = pool.submit(() -> attempt(() -> service.create(assistant, request(List.of(schedule), PLANNED))));
            try {
                assertThatThrownBy(() -> sender.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                clock.set(Instant.parse("2026-11-19T09:00:00Z"));
            } finally { release.countDown(); }
            assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo("OK");
            assertThat(sender.get(10, TimeUnit.SECONDS)).isEqualTo("VALIDATION_FAILED");
        } finally { release.countDown(); }
        assertThat(notices.count()).isZero();
    }

    @Test
    void secondInsertFailureRollsBackEntireBatch() {
        jdbc.execute("CREATE TRIGGER fail_notice BEFORE INSERT ON checkout_notices FOR EACH ROW BEGIN IF NEW.work_schedule_id="
                + secondSchedule + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test-only failure'; END IF; END");
        try { assertThatThrownBy(() -> service.create(assistant, request(List.of(schedule, secondSchedule), PLANNED))).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("DROP TRIGGER fail_notice"); }
        assertThat(notices.count()).isZero();
        assertThat(attendance.current(staff)).isNotNull();
    }

    @Test
    void databaseProtectsTimeUnitFutureForeignKeysAndAcknowledgement() {
        Long id = send(schedule, PLANNED);
        assertThatThrownBy(() -> jdbc.update("UPDATE checkout_notices SET planned_check_out_at='2026-11-19 09:15:00' WHERE id=?", id))
                .isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE checkout_notices SET planned_check_out_at=sent_at WHERE id=?", id))
                .isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE checkout_notices SET acknowledged_at=DATE_SUB(sent_at,INTERVAL 1 SECOND) WHERE id=?", id))
                .isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE checkout_notices SET work_schedule_id=999999 WHERE id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void v8UpgradePreservesOperationalClosureAndLedgerAndReexecutionPreservesNotices() throws Exception {
        String schema = "notice_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword()); var sql = c.createStatement()) {
            sql.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/" + schema);
        Flyway.configure().dataSource(url, "root", mysql.getPassword()).locations("classpath:db/migration").target("8").load().migrate();
        try (var c = DriverManager.getConnection(url, "root", mysql.getPassword()); var sql = c.createStatement()) {
            sql.executeUpdate("INSERT INTO users(id,email,password_hash,name,role,status,created_at,updated_at) VALUES (1,'preserve@example.com','preserve-hash','보존','MANAGER','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO stores(id,code,name,created_at,updated_at) VALUES (1,'PRESERVE','보존',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO work_schedules(id,user_id,store_id,work_date,start_at,status,created_by,updated_by,created_at,updated_at) VALUES (1,1,1,'2026-11-19','2026-11-19 01:00:00','SCHEDULED',1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO attendances(id,work_schedule_id,check_in_at,recorded_check_in_at,recorded_check_out_at,approval_status,approved_by,approved_at,closed_at,closure_type,created_at) VALUES (1,1,'2026-11-19 00:50:00','2026-11-19 01:00:00','2026-11-19 09:00:00','APPROVED',1,'2026-11-19 09:20:00','2026-11-19 09:20:00','ASSISTANT_APPROVAL',UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO attendance_checkout_requests(user_id,request_key,attendance_id,response_json,created_at) VALUES (1,'preserved',1,'{\"id\":1}',UTC_TIMESTAMP(6))");
        }
        var flyway = Flyway.configure().dataSource(url, "root", mysql.getPassword()).locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        try (var c = DriverManager.getConnection(url, "root", mysql.getPassword()); var sql = c.createStatement()) {
            sql.executeUpdate("INSERT INTO checkout_notices(work_schedule_id,planned_check_out_at,sent_by,sent_at) VALUES (1,'2026-11-19 09:00:00',1,'2026-11-19 08:40:00')");
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            flyway.validate();
            try (var rows = sql.executeQuery("SELECT a.check_out_at,a.closure_type,(SELECT COUNT(*) FROM checkout_notices),(SELECT response_json FROM attendance_checkout_requests WHERE request_key='preserved') FROM attendances a")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isNull();
                assertThat(rows.getString(2)).isEqualTo("ASSISTANT_APPROVAL");
                assertThat(rows.getInt(3)).isEqualTo(1);
                assertThat(rows.getString(4)).isEqualTo("{\"id\":1}");
            }
        }
    }

    private Long employee(String name, UserRole role, Long home) {
        var user = User.createPending(name + "@example.com", "test-hash", name, START);
        user.approve(role, home, manager, START);
        return users.saveAndFlush(user).getId();
    }
    private Long schedule(Long user, Long location, String date) {
        return work.create(manager, new CreateWorkScheduleRequest(user, location, OffsetDateTime.parse(date + "T10:00:00+09:00"), null)).id();
    }
    private CreateCheckoutNoticesRequest request(List<Long> ids, String time) { return new CreateCheckoutNoticesRequest(ids, OffsetDateTime.parse(time)); }
    private Long send(Long target, String time) { return service.create(assistant, request(List.of(target), time)).notices().getFirst().id(); }
    private MockHttpServletRequestBuilder create(List<Long> ids, String time) {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("workScheduleIds", ids);
        body.put("plannedCheckOutAt", time);
        return post(BASE).contentType(APPLICATION_JSON).content(json.writeValueAsString(body));
    }
    private MockHttpServletRequestBuilder mine() { return get(BASE + "/me").param("fromDate", "2026-11-19").param("toDate", "2026-11-19"); }
    private MockHttpServletRequestBuilder list() { return get(BASE).param("storeId", store.toString()).param("fromDate", "2026-11-19").param("toDate", "2026-11-19"); }
    private String token(Long actor) { return "Bearer " + sessions.open(actor).response().accessToken(); }
    private ResultActions call(MockHttpServletRequestBuilder query, Long actor) throws Exception { return mvc.perform(query.header("Authorization", token(actor))); }
    private List<String> race(Runnable a, Runnable b) throws Exception {
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(); return attempt(a); });
            var second = pool.submit(() -> { gate.await(); return attempt(b); });
            gate.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
    }
    private String attempt(Runnable command) {
        try { command.run(); return "OK"; }
        catch (BusinessException error) { return error.getErrorCode().name(); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("테스트 잠금 해제 시간 초과"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        void set(Instant value) { now.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig { @Bean @Primary MutableClock noticeClock() { return new MutableClock(); } }
}
