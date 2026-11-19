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
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AttendanceManagementApiTests.TimeConfig.class)
@Testcontainers
class AttendanceManagementApiTests extends JwtTestSupport {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4").withCommand("--log-bin-trust-function-creators=1");
    static final String BASE = "/api/v1/attendances";
    static final Instant START = Instant.parse("2026-11-18T00:50:00.123456Z");
    static final Instant NOW = Instant.parse("2026-11-18T09:20:00.123456Z");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired StoreRepository stores;
    @Autowired AttendanceRepository attendances;
    @Autowired WorkScheduleRepository schedules;
    @Autowired WorkScheduleService work;
    @Autowired AttendanceService attendance;
    @Autowired AttendanceManagementService management;
    @Autowired AuthSessionService sessions;
    @Autowired MutableClock clock;
    @Autowired JsonMapper json;
    Long manager, staff, assistant, otherAssistant, transfer, store, otherStore, schedule, id;

    @BeforeEach
    void prepareIsolatedDatabase() {
        clock.set(START);
        jdbc.update("DELETE FROM attendance_checkout_requests");
        jdbc.update("DELETE FROM attendances");
        schedules.deleteAll();
        jdbc.update("UPDATE auth_sessions SET replaced_by_id=NULL");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE users SET approved_by=NULL");
        users.deleteAll();
        stores.deleteAll();
        store = stores.saveAndFlush(Store.create("MANAGEMENT", "운영 매장", START)).getId();
        otherStore = stores.saveAndFlush(Store.create("OTHER", "타 매장", START)).getId();
        manager = users.saveAndFlush(User.createInitialManager("manager@example.com", "test-hash", "총괄", START)).getId();
        staff = employee("staff", UserRole.STAFF, store);
        assistant = employee("assistant", UserRole.ASSISTANT_MANAGER, store);
        otherAssistant = employee("other-assistant", UserRole.ASSISTANT_MANAGER, otherStore);
        transfer = employee("transfer", UserRole.STAFF, otherStore);
        schedule = schedule(staff, store, "2026-11-18");
        id = attendance.checkIn(staff, new CheckInRequest(schedule)).id();
        clock.set(NOW);
    }

    @Test
    void approveEmployeeCheckoutPreservesActualTimesAndStoresApproverWithoutReason() throws Exception {
        clock.set(Instant.parse("2026-11-18T09:12:00.123456Z"));
        attendance.checkOut(staff, id, "K1");
        clock.set(NOW);
        call(approve(id, "2026-11-18T18:00:00+09:00"), assistant).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkInAt").value(START.toString()))
                .andExpect(jsonPath("$.checkOutAt").value("2026-11-18T09:12:00.123456Z"))
                .andExpect(jsonPath("$.recordedCheckOutAt").value("2026-11-18T09:00:00Z"))
                .andExpect(jsonPath("$.approvedBy").value(assistant)).andExpect(jsonPath("$.approvedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.approvalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.closureType").value("EMPLOYEE_CHECKOUT"))
                .andExpect(jsonPath("$.closedAt").value("2026-11-18T09:12:00.123456Z"));
    }

    @Test
    void missingCheckoutCanBeApprovedAndClosedWithoutInventingEmployeeTime() throws Exception {
        call(approve(id, "2026-11-18T18:00:00+09:00"), assistant).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkOutAt").isEmpty()).andExpect(jsonPath("$.closureType").value("ASSISTANT_APPROVAL"))
                .andExpect(jsonPath("$.closedAt").value(NOW.toString())).andExpect(jsonPath("$.approvalStatus").value("APPROVED"));
        call(get(BASE + "/me/current"), staff).andExpect(status().isNoContent());
        assertThat(attendances.findActive(staff)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT check_out_at FROM attendances WHERE id=?", LocalDateTime.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT closed_at FROM attendances WHERE id=?", LocalDateTime.class, id))
                .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    void lateEmployeeRequestReturnsOperationalClosureAndNeverAddsActualTime() throws Exception {
        management.approve(assistant, id, request("2026-11-18T18:00:00+09:00"));
        clock.set(NOW.plusSeconds(3600));
        var first = call(checkout(id, "K1"), staff).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkOutAt").isEmpty()).andExpect(jsonPath("$.closureType").value("ASSISTANT_APPROVAL"))
                .andReturn().getResponse().getContentAsString();
        call(checkout(id, "K1"), staff).andExpect(status().isOk()).andExpect(content().json(first));
        assertThat(attendances.findById(id).orElseThrow().getCheckOutAt()).isNull();
    }

    @Test
    void operationalClosureAllowsNextDayCheckInAndOldRetryDoesNotCloseIt() throws Exception {
        management.approve(assistant, id, request("2026-11-18T18:00:00+09:00"));
        Long nextSchedule = schedule(staff, store, "2026-11-19");
        clock.set(START.plusSeconds(86400));
        Long next = attendance.checkIn(staff, new CheckInRequest(nextSchedule)).id();
        call(checkout(id, "old-request"), staff).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        call(get(BASE + "/me/current"), staff).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(next));
        assertThat(attendances.findById(next).orElseThrow().isClosed()).isFalse();
    }

    @Test
    void assistantCanApproveOwnScheduleAndHistoryIdentifiesSelfApproval() throws Exception {
        clock.set(START);
        Long own = attendance.checkIn(assistant, new CheckInRequest(schedule(assistant, store, "2026-11-18"))).id();
        clock.set(NOW);
        call(approve(own, "2026-11-18T18:00:00+09:00"), assistant).andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedBy").value(assistant));
        call(mine(), assistant).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].selfApproved").value(true));
    }

    @Test
    void scheduleStoreControlsPermissionInsteadOfEmployeeHomeStore() throws Exception {
        clock.set(START);
        Long transferred = attendance.checkIn(transfer, new CheckInRequest(schedule(transfer, store, "2026-11-18"))).id();
        clock.set(NOW);
        call(approve(transferred, "2026-11-18T18:00:00+09:00"), otherAssistant).andExpect(status().isForbidden());
        call(approve(transferred, "2026-11-18T18:00:00+09:00"), assistant).andExpect(status().isOk());
    }

    @Test
    void managerStaffAndOtherStoreAssistantCannotApprove() throws Exception {
        for (Long actor : List.of(manager, staff, otherAssistant)) {
            call(approve(id, "2026-11-18T18:00:00+09:00"), actor).andExpect(status().isForbidden());
        }
        assertThat(attendances.findById(id).orElseThrow().isClosed()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED", "INACTIVE"})
    void nonActiveApproverCannotApproveOrReadHistory(String state) throws Exception {
        String token = token(assistant);
        jdbc.update("UPDATE users SET status=? WHERE id=?", state, assistant);
        mvc.perform(approve(id, "2026-11-18T18:00:00+09:00").header("Authorization", token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_" + state));
        mvc.perform(list().header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(mine().header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test
    void inactiveTargetAndStoreCanBeManagedAsHistory() throws Exception {
        jdbc.update("UPDATE users SET status='INACTIVE' WHERE id=?", staff);
        jdbc.update("UPDATE stores SET active=false WHERE id=?", store);
        call(approve(id, "2026-11-18T18:00:00+09:00"), assistant).andExpect(status().isOk());
        call(list(), assistant).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-18T18:15:00+09:00", "2026-11-18T18:00:01+09:00", "2026-11-18T18:00:00.000001+09:00"})
    void nonHalfHourTimeIsRejected(String time) throws Exception {
        call(approve(id, time), assistant).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CHECKOUT_TIME_UNIT"));
        assertThat(attendances.findById(id).orElseThrow().isClosed()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-18T09:30:00+09:00", "2026-11-17T18:00:00+09:00", "2026-11-19T00:00:00+09:00", "2026-11-18T18:30:00+09:00"})
    void beforeRecognizedInOtherDateAndFutureTimesAreRejected(String time) throws Exception {
        call(approve(id, time), assistant).andExpect(status().isBadRequest());
        assertThat(attendances.findById(id).orElseThrow().getApprovalStatus().name()).isEqualTo("PENDING");
    }

    @Test
    void utcInputIsValidatedAgainstSeoulHalfHourAndWorkDate() throws Exception {
        call(approve(id, "2026-11-18T09:00:00Z"), assistant).andExpect(status().isOk())
                .andExpect(jsonPath("$.recordedCheckOutAt").value("2026-11-18T09:00:00Z"));
    }

    @Test
    void approvalCannotBeRepeatedOrChanged() throws Exception {
        var first = call(approve(id, "2026-11-18T18:00:00+09:00"), assistant).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        clock.set(NOW.plusSeconds(300));
        call(approve(id, "2026-11-18T17:30:00+09:00"), assistant).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTENDANCE_ALREADY_APPROVED"));
        assertThat(json.writeValueAsString(AttendanceResponse.from(attendances.findById(id).orElseThrow()))).isEqualTo(first);
    }

    @Test
    void checkoutReplayKeepsOriginalSnapshotWhileHistoryShowsLatestApproval() throws Exception {
        var first = call(checkout(id, "K1"), staff).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        management.approve(assistant, id, request("2026-11-18T18:00:00+09:00"));
        call(checkout(id, "K1"), staff).andExpect(status().isOk()).andExpect(content().json(first));
        call(mine(), staff).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].approvalStatus").value("APPROVED"));
    }

    @Test
    void oldSnapshotsWithoutClosureFieldsRemainReadable() throws Exception {
        attendance.checkOut(staff, id, "K1");
        jdbc.update("UPDATE attendance_checkout_requests SET response_json=JSON_REMOVE(response_json,'$.closedAt','$.closureType')");
        call(checkout(id, "K1"), staff).andExpect(status().isOk()).andExpect(jsonPath("$.checkOutAt").isNotEmpty());
    }

    @Test
    void historyHasStableOrderingPaginationAndApprovalFilter() throws Exception {
        clock.set(START);
        Long second = attendance.checkIn(assistant, new CheckInRequest(schedule(assistant, store, "2026-11-18"))).id();
        Long third = attendance.checkIn(transfer, new CheckInRequest(schedule(transfer, store, "2026-11-18"))).id();
        attendance.checkIn(otherAssistant, new CheckInRequest(schedule(otherAssistant, otherStore, "2026-11-18")));
        clock.set(NOW);
        management.approve(assistant, id, request("2026-11-18T18:00:00+09:00"));
        call(list().param("size", "2"), manager).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].id").value(id)).andExpect(jsonPath("$.content[1].id").value(second))
                .andExpect(jsonPath("$.content[0].storeId").value(store)).andExpect(jsonPath("$.content[0].userId").value(staff));
        call(list().param("size", "2").param("page", "1"), assistant).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(third));
        call(list().param("approvalStatus", "APPROVED"), assistant).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        call(list().param("approvalStatus", "PENDING"), assistant).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void historyPeriodUsesWorkDateIncludingBothEndsNotLateCheckoutDate() throws Exception {
        clock.set(Instant.parse("2026-11-19T01:00:00Z"));
        attendance.checkOut(staff, id, "late");
        call(mine(), staff).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].workDate").value("2026-11-18"));
        call(get(BASE + "/me").param("fromDate", "2026-11-19").param("toDate", "2026-11-19"), staff)
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void ownHistoryIgnoresSpoofedUserIdAndStoreHistoryRequiresManagementPermission() throws Exception {
        call(mine().param("userId", staff.toString()), transfer).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        call(list(), staff).andExpect(status().isForbidden());
        call(list(), otherAssistant).andExpect(status().isForbidden());
        call(list(), manager).andExpect(status().isOk());
        call(list(), assistant).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=2147483647", "approvalStatus=UNKNOWN", "fromDate=bad", "toDate=2026-11-17"})
    void invalidHistoryFiltersAreRejected(String parameter) throws Exception {
        var pair = parameter.split("=");
        var query = get(BASE + "/me")
                .param("fromDate", pair[0].equals("fromDate") ? pair[1] : "2026-11-18")
                .param("toDate", pair[0].equals("toDate") ? pair[1] : "2026-11-18");
        if (!pair[0].equals("fromDate") && !pair[0].equals("toDate")) query.param(pair[0], pair[1]);
        call(query, staff).andExpect(status().isBadRequest());
    }

    @Test
    void missingParametersAndUnknownTargetsAreRejected() throws Exception {
        call(get(BASE + "/me"), staff).andExpect(status().isBadRequest());
        call(get(BASE).param("fromDate", "2026-11-18").param("toDate", "2026-11-18"), manager).andExpect(status().isBadRequest());
        call(get(BASE).param("storeId", "999999").param("fromDate", "2026-11-18").param("toDate", "2026-11-18"), manager)
                .andExpect(status().isNotFound());
        call(approve(999999L, "2026-11-18T18:00:00+09:00"), assistant).andExpect(status().isNotFound());
        call(approve(0L, "2026-11-18T18:00:00+09:00"), assistant).andExpect(status().isBadRequest());
    }

    @Test
    void anonymousCookieAndForgedBodiesCannotApprove() throws Exception {
        mvc.perform(approve(id, "2026-11-18T18:00:00+09:00")).andExpect(status().isUnauthorized());
        mvc.perform(mine()).andExpect(status().isUnauthorized());
        mvc.perform(approve(id, "2026-11-18T18:00:00+09:00").cookie(new jakarta.servlet.http.Cookie("access_token", token(assistant))))
                .andExpect(status().isUnauthorized());
        for (String body : List.of("{}", "{\"recordedCheckOutAt\":\"2026-11-18T18:00:00\"}",
                "{\"recordedCheckOutAt\":\"2026-11-18T18:00:00+09:00\",\"approvedBy\":1}")) {
            call(post(BASE + "/" + id + "/approve").contentType(APPLICATION_JSON).content(body), assistant).andExpect(status().isBadRequest());
        }
    }

    @Test
    void concurrentApprovalsHaveOneWinnerAndPreserveWinningRecord() throws Exception {
        var results = race(() -> management.approve(assistant, id, request("2026-11-18T18:00:00+09:00")),
                () -> management.approve(assistant, id, request("2026-11-18T17:30:00+09:00")));
        assertThat(results).containsExactlyInAnyOrder("OK", "ATTENDANCE_ALREADY_APPROVED");
        assertThat(attendances.findById(id).orElseThrow().getRecordedCheckOutAt())
                .isIn(Instant.parse("2026-11-18T09:00:00Z"), Instant.parse("2026-11-18T08:30:00Z"));
    }

    @Test
    void concurrentEmployeeCheckoutAndApprovalPreserveActualSourceAndCloseOnce() throws Exception {
        assertThat(race(() -> attendance.checkOut(staff, id, "K1"),
                () -> management.approve(assistant, id, request("2026-11-18T18:00:00+09:00"))))
                .containsExactly("OK", "OK");
        var row = attendances.findById(id).orElseThrow();
        assertThat(row.getApprovalStatus().name()).isEqualTo("APPROVED");
        assertThat(row.getClosedAt()).isEqualTo(NOW);
        assertThat(row.getClosureType().name()).isEqualTo(row.getCheckOutAt() == null ? "ASSISTANT_APPROVAL" : "EMPLOYEE_CHECKOUT");
        assertThat(attendances.findActive(staff)).isEmpty();
    }

    @Test
    void twoAssistantsCanConcurrentlyApproveEachOthersAttendanceWithoutLockOrderInversion() throws Exception {
        Long secondAssistant = employee("second-assistant", UserRole.ASSISTANT_MANAGER, store);
        clock.set(START);
        Long first = attendance.checkIn(assistant, new CheckInRequest(schedule(assistant, store, "2026-11-18"))).id();
        Long second = attendance.checkIn(secondAssistant, new CheckInRequest(schedule(secondAssistant, store, "2026-11-18"))).id();
        clock.set(NOW);
        assertThat(race(() -> management.approve(assistant, second, request("2026-11-18T18:00:00+09:00")),
                () -> management.approve(secondAssistant, first, request("2026-11-18T18:00:00+09:00"))))
                .containsExactly("OK", "OK");
        assertThat(attendances.findById(first).orElseThrow().getApprovedBy()).isEqualTo(secondAssistant);
        assertThat(attendances.findById(second).orElseThrow().getApprovedBy()).isEqualTo(assistant);
    }

    @Test
    void concurrentOperationalClosureAndNextDayCheckInRemainConsistent() throws Exception {
        Long next = schedule(staff, store, "2026-11-19");
        clock.set(START.plusSeconds(86400));
        var results = race(() -> management.approve(assistant, id, request("2026-11-18T18:00:00+09:00")),
                () -> attendance.checkIn(staff, new CheckInRequest(next)));
        assertThat(results.getFirst()).isEqualTo("OK");
        assertThat(results.getLast()).isIn("OK", "ALREADY_CHECKED_IN");
        assertThat(attendances.findById(id).orElseThrow().isClosed()).isTrue();
        if (results.getLast().equals("ALREADY_CHECKED_IN")) attendance.checkIn(staff, new CheckInRequest(next));
        assertThat(attendances.findActive(staff)).hasSize(1);
        assertThat(attendances.findActive(staff).getFirst().getWorkScheduleId()).isEqualTo(next);
    }

    @Test
    void failedApprovalRollsBackClosureAndLeavesCurrentAttendanceActive() throws Exception {
        jdbc.execute("CREATE TRIGGER fail_approval BEFORE UPDATE ON attendances FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test-only failure'");
        try { assertThatThrownBy(() -> management.approve(assistant, id, request("2026-11-18T18:00:00+09:00"))).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("DROP TRIGGER fail_approval"); }
        var row = attendances.findById(id).orElseThrow();
        assertThat(row.getRecordedCheckOutAt()).isNull();
        assertThat(row.getClosedAt()).isNull();
        assertThat(row.getApprovedBy()).isNull();
        assertThat(row.getApprovalStatus().name()).isEqualTo("PENDING");
        call(get(BASE + "/me/current"), staff).andExpect(status().isOk());
        call(approve(id, "2026-11-18T18:00:00+09:00"), assistant).andExpect(status().isOk());
    }

    @Test
    void databaseRejectsClosureWithoutSourceAndInventedEmployeeTimestamp() {
        assertThatThrownBy(() -> jdbc.update("UPDATE attendances SET closed_at=check_in_at WHERE id=?", id))
                .isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE attendances SET check_out_at=check_in_at WHERE id=?", id))
                .isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class);
        management.approve(assistant, id, request("2026-11-18T18:00:00+09:00"));
        assertThatThrownBy(() -> jdbc.update("UPDATE attendances SET check_out_at=closed_at WHERE id=?", id))
                .isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class);
    }

    @Test
    void v7UpgradePreservesOriginalApprovalAndLedgerAndBackfillsEmployeeClosure() throws Exception {
        String schema = "operational_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword()); var sql = c.createStatement()) {
            sql.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/" + schema);
        Flyway.configure().dataSource(url, "root", mysql.getPassword()).locations("classpath:db/migration").target("7").load().migrate();
        try (var c = DriverManager.getConnection(url, "root", mysql.getPassword()); var sql = c.createStatement()) {
            sql.executeUpdate("INSERT INTO users(id,email,password_hash,name,role,status,created_at,updated_at) VALUES (1,'preserve@example.com','preserve-hash','보존','MANAGER','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO stores(id,code,name,created_at,updated_at) VALUES (1,'PRESERVE','보존',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO work_schedules(id,user_id,store_id,work_date,start_at,status,created_by,updated_by,created_at,updated_at) VALUES (1,1,1,'2026-11-18','2026-11-18 01:00:00','SCHEDULED',1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),(2,1,1,'2026-11-19','2026-11-19 01:00:00','SCHEDULED',1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO attendances(id,work_schedule_id,check_in_at,recorded_check_in_at,check_out_at,recorded_check_out_at,approval_status,approved_by,approved_at,created_at) VALUES (1,1,'2026-11-18 00:50:00','2026-11-18 01:00:00','2026-11-18 09:12:00','2026-11-18 09:00:00','APPROVED',1,'2026-11-18 09:20:00',UTC_TIMESTAMP(6)),(2,2,'2026-11-19 01:15:00','2026-11-19 01:15:00',NULL,NULL,'PENDING',NULL,NULL,UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO attendance_checkout_requests(user_id,request_key,attendance_id,response_json,created_at) VALUES (1,'preserved',1,'{\"id\":1}',UTC_TIMESTAMP(6))");
        }
        var flyway = Flyway.configure().dataSource(url, "root", mysql.getPassword()).locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        try (var c = DriverManager.getConnection(url, "root", mysql.getPassword()); var sql = c.createStatement();
                var rows = sql.executeQuery("SELECT check_out_at,recorded_check_out_at,approval_status,closed_at,closure_type,(SELECT response_json FROM attendance_checkout_requests WHERE request_key='preserved') FROM attendances ORDER BY id")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).startsWith("2026-11-18 09:12:00");
            assertThat(rows.getString(2)).startsWith("2026-11-18 09:00:00");
            assertThat(rows.getString(3)).isEqualTo("APPROVED");
            assertThat(rows.getString(4)).startsWith("2026-11-18 09:12:00");
            assertThat(rows.getString(5)).isEqualTo("EMPLOYEE_CHECKOUT");
            assertThat(rows.getString(6)).isEqualTo("{\"id\":1}");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isNull();
            assertThat(rows.getString(4)).isNull();
            assertThat(rows.getString(5)).isNull();
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
    private ApproveAttendanceRequest request(String time) { return new ApproveAttendanceRequest(OffsetDateTime.parse(time)); }
    private MockHttpServletRequestBuilder approve(Long target, String time) {
        return post(BASE + "/" + target + "/approve").contentType(APPLICATION_JSON).content("{\"recordedCheckOutAt\":\"" + time + "\"}");
    }
    private MockHttpServletRequestBuilder checkout(Long target, String key) {
        return post(BASE + "/me/check-out").header("X-Attendance-Id", target).header("Idempotency-Key", key);
    }
    private MockHttpServletRequestBuilder mine() { return get(BASE + "/me").param("fromDate", "2026-11-18").param("toDate", "2026-11-18"); }
    private MockHttpServletRequestBuilder list() { return get(BASE).param("storeId", store.toString()).param("fromDate", "2026-11-18").param("toDate", "2026-11-18"); }
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
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        void set(Instant value) { now.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig { @Bean @Primary MutableClock managementClock() { return new MutableClock(); } }
}
