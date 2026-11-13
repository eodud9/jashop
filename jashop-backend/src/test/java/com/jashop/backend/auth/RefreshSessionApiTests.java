package com.jashop.backend.auth;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.jashop.backend.auth.api.RefreshCookies;
import com.jashop.backend.auth.session.infrastructure.AuthSessionRepository;
import com.jashop.backend.auth.token.AccessTokenService;
import com.jashop.backend.store.domain.Store;
import com.jashop.backend.store.infrastructure.StoreRepository;
import com.jashop.backend.support.JwtTestSupport;
import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.infrastructure.UserRepository;
import jakarta.servlet.http.Cookie;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpHeaders.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RefreshSessionApiTests.TimeConfig.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class RefreshSessionApiTests extends JwtTestSupport {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withCommand("--log-bin-trust-function-creators=1"); // 원자성 검증용 테스트 트리거에 한정
    static final Instant NOW = Instant.parse("2026-11-11T05:00:00Z");
    static final long SEVEN_DAYS = 604800;
    static final String PASSWORD = "test-only-refresh-password";
    static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired MutableClock time;
    @Autowired UserRepository users;
    @Autowired StoreRepository stores;
    @Autowired AuthSessionRepository sessions;
    @Autowired com.jashop.backend.auth.session.application.AuthSessionService sessionService;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtDecoder decoder;
    @Autowired JwtEncoder encoder;
    @Autowired AccessTokenService access;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    String hash;
    Long userId;
    Long storeId;

    @BeforeAll
    void hashPassword() { hash = passwords.encode(PASSWORD); }

    @BeforeEach
    void resetIsolatedTestDatabase() {
        time.set(NOW);
        jdbc.update("UPDATE auth_sessions SET replaced_by_id = NULL");
        sessions.deleteAll();
        jdbc.update("UPDATE users SET approved_by = NULL");
        users.deleteAll();
        stores.deleteAll();
        storeId = stores.saveAndFlush(Store.create("REFRESH", "재발급 매장", NOW)).getId();
        userId = users.saveAndFlush(User.createInitialManager("refresh@example.com", hash, "총괄", NOW)).getId();
    }

    @Test
    void csrfEndpointProvidesMaskedHeaderTokenAndSecureCookieWithoutHttpSession() throws Exception {
        MvcResult response = mvc.perform(get("/api/v1/auth/csrf").secure(true).header(ORIGIN, AUTH_ORIGIN))
                .andExpect(status().isOk()).andExpect(header().string(CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN")).andReturn();
        String cookie = setCookie(response, "XSRF-TOKEN");
        assertThat(cookie).contains("Secure", "HttpOnly", "Path=/api/v1/auth");
        // CookieCsrfTokenRepository는 Servlet Cookie 속성에 SameSite를 설정한다.
        // MockHttpServletResponse의 문자열 직렬화는 일반 Cookie의 이 확장 속성을 생략한다.
        assertThat(response.getResponse().getCookie("XSRF-TOKEN").getAttribute("SameSite")).isEqualTo("Strict");
        assertThat(JSON.readTree(response.getResponse().getContentAsString()).get("token").asText())
                .isNotEqualTo(cookieValue(cookie));
        assertThat(response.getRequest().getSession(false)).isNull();
    }

    @Test
    void loginStoresOnlyHashAndSetsSevenDayCookieAndSessionBoundAccess(CapturedOutput output) throws Exception {
        Login login = login();
        assertThat(setCookie(login.result(), RefreshCookies.NAME)).contains("HttpOnly", "Secure", "SameSite=Strict",
                "Path=/api/v1/auth", "Max-Age=604800");
        assertThat(login.result().getResponse().getContentAsString()).doesNotContain(login.refresh().getValue(), hash, PASSWORD);
        assertThat(java.util.Base64.getUrlDecoder().decode(login.refresh().getValue())).hasSize(32);
        var stored = sessions.findAll().getFirst();
        assertThat(stored.getTokenHash()).hasSize(64).doesNotContain(login.refresh().getValue());
        assertThat(stored.getFamilyId()).isEqualTo(login.family());
        assertThat(stored.getExpiresAt()).isEqualTo(NOW.plusSeconds(SEVEN_DAYS));
        assertThat(stored.getFamilyRevokedAt()).isNull();
        assertThat(decoder.decode(login.access()).getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(login.result().getRequest().getSession(false)).isNull();
        assertThat(output.getAll()).doesNotContain(login.refresh().getValue(), login.access(), hash, PASSWORD, JWT_SECRET);
    }

    @Test
    void databaseProtectsSessionHashesReferencesExpiryAndUtcTime() throws Exception {
        login();
        var root = sessions.findAll().getFirst();
        assertThat(jdbc.queryForObject("SELECT issued_at FROM auth_sessions WHERE id=?", java.time.LocalDateTime.class, root.getId()))
                .isEqualTo(java.time.LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO auth_sessions(user_id,family_id,token_hash,issued_at,expires_at,created_at) "
                + "SELECT user_id,family_id,token_hash,issued_at,expires_at,created_at FROM auth_sessions WHERE id=?", root.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("uk_auth_sessions_token_hash");
        assertThatThrownBy(() -> jdbc.update("UPDATE auth_sessions SET user_id=? WHERE id=?", Long.MAX_VALUE, root.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE auth_sessions SET replaced_by_id=? WHERE id=?", Long.MAX_VALUE, root.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE auth_sessions SET expires_at=issued_at WHERE id=?", root.getId()))
                .isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class).hasMessageContaining("ck_auth_sessions_expiry");
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    void loginRequiresOriginAndActualCookieCsrfPair() throws Exception {
        var csrf = csrf();
        mvc.perform(post("/api/v1/auth/login").secure(true).contentType(APPLICATION_JSON).content(credentials()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/login").secure(true).header(ORIGIN, AUTH_ORIGIN)
                        .contentType(APPLICATION_JSON).content(credentials()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/login").secure(true).cookie(csrf.cookie()).header(csrf.header(), csrf.token())
                        .contentType(APPLICATION_JSON).content(credentials()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/login").secure(true).cookie(csrf.cookie()).header(ORIGIN, AUTH_ORIGIN)
                        .header(csrf.header(), "wrong-token").contentType(APPLICATION_JSON).content(credentials()))
                .andExpect(status().isForbidden());
        assertThat(sessions.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "null"})
    void invalidOriginCannotLoginEvenWithValidCsrf(String origin) throws Exception {
        var csrf = csrf();
        mvc.perform(post("/api/v1/auth/login").secure(true).cookie(csrf.cookie()).header(ORIGIN, origin)
                        .header(csrf.header(), csrf.token()).contentType(APPLICATION_JSON).content(credentials()))
                .andExpect(status().isForbidden());
        assertThat(sessions.count()).isZero();
    }

    @Test
    void cookieMutationsRejectMissingCsrfWithoutRevokingLegitimateSession() throws Exception {
        Login login = login();
        for (String endpoint : List.of("refresh", "logout")) {
            mvc.perform(post("/api/v1/auth/" + endpoint).secure(true).header(ORIGIN, AUTH_ORIGIN).cookie(login.refresh()))
                    .andExpect(status().isForbidden());
        }
        me(login.access(), 200);
        assertThat(sessions.findAll().getFirst().getFamilyRevokedAt()).isNull();
    }

    @Test
    void normalRotationPreservesFamilyAndExpiryAndDoesNotRevokeExistingAccess() throws Exception {
        Login login = login();
        time.set(NOW.plusSeconds(60));
        MvcResult result = refresh(login.refresh(), null, 200);
        Login next = tokens(result);
        assertThat(next.family()).isEqualTo(login.family());
        assertThat(next.refresh().getValue()).isNotEqualTo(login.refresh().getValue());
        assertThat(setCookie(result, RefreshCookies.NAME)).contains("Max-Age=604740");
        var rows = sessions.findAll().stream().sorted(java.util.Comparator.comparing(s -> s.getId())).toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().getRevokedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(rows.getFirst().getReplacedById()).isEqualTo(rows.getLast().getId());
        assertThat(rows.getLast().getExpiresAt()).isEqualTo(NOW.plusSeconds(SEVEN_DAYS));
        assertThat(rows.getLast().getRevokedAt()).isNull();
        me(login.access(), 200);
        me(next.access(), 200);
    }

    @Test
    void expiredAccessHeaderDoesNotPreventRefresh() throws Exception {
        Login login = login();
        time.set(NOW.plusSeconds(901));
        me(login.access(), 401);
        Login next = tokens(refresh(login.refresh(), login.access(), 200));
        me(next.access(), 200);
        assertThat(decoder.decode(next.access()).getExpiresAt()).isEqualTo(time.instant().plusSeconds(900));
    }

    @Test
    void seventhDayIsAbsoluteAndRotationNeverExtendsIt() throws Exception {
        Login login = login();
        time.set(NOW.plusSeconds(SEVEN_DAYS - 60));
        Login next = tokens(refresh(login.refresh(), null, 200));
        assertThat(setCookie(next.result(), RefreshCookies.NAME)).contains("Max-Age=60");
        me(next.access(), 200);
        time.set(NOW.plusSeconds(SEVEN_DAYS));
        // JWT 자체는 아직 만료 전이어도 로그인 계열이 7일에 도달하면 거부한다.
        me(next.access(), 401);
        var expired = refresh(next.refresh(), null, 401);
        assertThat(code(expired)).isEqualTo("REFRESH_TOKEN_EXPIRED");
        assertThat(setCookie(expired, RefreshCookies.NAME)).contains("Max-Age=0");
    }

    @Test
    void reuseCommitsFamilyRevocationIncludingRotatedTokenButKeepsOtherLogin() throws Exception {
        Login first = login();
        Login otherDevice = login();
        Login rotated = tokens(refresh(first.refresh(), null, 200));
        MvcResult reused = refresh(first.refresh(), null, 401);
        assertThat(code(reused)).isEqualTo("REFRESH_TOKEN_REUSED");
        var root = sessions.findAll().stream().filter(s -> s.getFamilyId().equals(first.family()))
                .min(java.util.Comparator.comparing(s -> s.getId())).orElseThrow();
        assertThat(root.getFamilyRevokedAt()).isEqualTo(NOW);
        assertThat(sessions.findAll().stream().filter(s -> s.getFamilyId().equals(first.family())))
                .allSatisfy(s -> assertThat(s.getRevokedAt()).isNotNull());
        me(first.access(), 401);
        me(rotated.access(), 401);
        assertThat(code(refresh(rotated.refresh(), null, 401))).isEqualTo("REFRESH_TOKEN_INVALID");
        me(otherDevice.access(), 200);
        refresh(otherDevice.refresh(), null, 200);
    }

    @Test
    void logoutImmediatelyRevokesAllFamilyAccessAndRefreshAndIsIdempotent() throws Exception {
        Login first = login();
        Login otherDevice = login();
        Login rotated = tokens(refresh(first.refresh(), null, 200));
        var response = logout(first.refresh(), 204); // 이전 회전 쿠키로도 현재 계열 전체 폐기
        assertThat(setCookie(response, RefreshCookies.NAME)).contains("Max-Age=0", "HttpOnly", "Secure", "SameSite=Strict");
        me(first.access(), 401);
        me(rotated.access(), 401);
        refresh(rotated.refresh(), null, 401);
        time.set(NOW.plusSeconds(1));
        logout(first.refresh(), 204);
        var root = sessions.findAll().stream().filter(s -> s.getFamilyId().equals(first.family()))
                .min(java.util.Comparator.comparing(s -> s.getId())).orElseThrow();
        assertThat(root.getFamilyRevokedAt()).isEqualTo(NOW);
        me(otherDevice.access(), 200);
    }

    @Test
    void unknownOrMissingLogoutCookieOnlyClearsCookieAndDoesNotAffectOtherSessions() throws Exception {
        Login login = login();
        logout(null, 204);
        logout(new Cookie(RefreshCookies.NAME, "A".repeat(43)), 204);
        me(login.access(), 200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED", "INACTIVE"})
    void nonActiveRefreshRevokesFamilyEvenIfAccountLaterBecomesActiveAgain(String state) throws Exception {
        Login login = login();
        jdbc.update("UPDATE users SET status=? WHERE id=?", state, userId);
        assertThat(code(refresh(login.refresh(), null, 403))).isEqualTo("ACCOUNT_" + state);
        jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=?", userId);
        me(login.access(), 401);
        refresh(login.refresh(), null, 401);
    }

    @Test
    void refreshedUserDtoAndPermissionsComeFromCurrentDatabase() throws Exception {
        Login login = login();
        jdbc.update("UPDATE users SET role='STAFF', home_store_id=? WHERE id=?", storeId, userId);
        MvcResult response = refresh(login.refresh(), null, 200);
        assertThat(JSON.readTree(response.getResponse().getContentAsString()).get("user").get("role").asText()).isEqualTo("STAFF");
        mvc.perform(get("/api/v1/admin/users/pending").header(AUTHORIZATION, "Bearer " + tokens(response).access()))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "malformed", "A", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void invalidRefreshCookiesReturn401AndClearCookie(String value) throws Exception {
        var result = refresh(new Cookie(RefreshCookies.NAME, value), null, 401);
        assertThat(code(result)).isEqualTo("REFRESH_TOKEN_INVALID");
        assertThat(setCookie(result, RefreshCookies.NAME)).contains("Max-Age=0");
    }

    @Test
    void accessHeaderQueryAndJsonCannotReplaceRefreshCookie() throws Exception {
        Login login = login();
        var csrf = csrf();
        mvc.perform(post("/api/v1/auth/refresh").secure(true).cookie(csrf.cookie()).header(ORIGIN, AUTH_ORIGIN)
                        .header(csrf.header(), csrf.token()).header(AUTHORIZATION, "Bearer " + login.access())
                        .param("refreshToken", login.refresh().getValue()).contentType(APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("refreshToken", login.refresh().getValue()))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
        me(login.access(), 200);
    }

    @Test
    void sessionlessLegacyUnknownAndWrongOwnerSidCannotAuthenticate() throws Exception {
        Login login = login();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("https://jashop.local").audience(List.of("jashop-api"))
                .subject(userId.toString()).issuedAt(NOW).expiresAt(NOW.plusSeconds(900)).claim("token_use", "access").build();
        String legacy = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        me(legacy, 401);
        me(access.issue(userId, UUID.randomUUID().toString()), 401);
        me(access.issue(Long.MAX_VALUE, login.family()), 401);
    }

    @Test
    void concurrentNonRootRefreshOnlyRotatesOnceThenReplayClosesFamily() throws Exception {
        Login current = tokens(refresh(login().refresh(), null, 200));
        var firstCsrf = csrf();
        var secondCsrf = csrf();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return refreshWithCsrf(current.refresh(), firstCsrf); });
            var second = executor.submit(() -> { start.await(); return refreshWithCsrf(current.refresh(), secondCsrf); });
            start.countDown();
            MvcResult a = first.get(15, TimeUnit.SECONDS);
            MvcResult b = second.get(15, TimeUnit.SECONDS);
            assertThat(List.of(a.getResponse().getStatus(), b.getResponse().getStatus())).containsExactlyInAnyOrder(200, 401);
            MvcResult winner = a.getResponse().getStatus() == 200 ? a : b;
            MvcResult loser = a.getResponse().getStatus() == 401 ? a : b;
            assertThat(code(loser)).isEqualTo("REFRESH_TOKEN_REUSED");
            me(tokens(winner).access(), 401);
            assertThat(sessions.count()).isEqualTo(3); // 최초 + 사전 회전 + 경쟁 승자 한 번
        }
    }

    @Test
    void concurrentRefreshAndLogoutCannotResurrectFamily() throws Exception {
        Login initial = login();
        Login current = tokens(refresh(initial.refresh(), null, 200));
        var refreshCsrf = csrf();
        var logoutCsrf = csrf();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var refreshing = executor.submit(() -> { start.await(); return refreshWithCsrf(current.refresh(), refreshCsrf); });
            var loggingOut = executor.submit(() -> { start.await(); return logoutWithCsrf(initial.refresh(), logoutCsrf); });
            start.countDown();
            MvcResult result = refreshing.get(15, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isIn(200, 401);
            assertThat(loggingOut.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(204);
            if (result.getResponse().getStatus() == 200) me(tokens(result).access(), 401);
        }
        me(initial.access(), 401);
        me(current.access(), 401);
        refresh(current.refresh(), null, 401);
    }

    @Test
    void logoutCannotCompleteWhileFamilyRootIsLocked() {
        try {
            Login login = login();
            Long rootId = sessions.findAll().getFirst().getId();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                sessions.findByIdForUpdate(rootId).orElseThrow();
                try (var executor = Executors.newSingleThreadExecutor()) {
                    var attempt = executor.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                        jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
                        try {
                            // HTTP가 아닌 서비스의 실제 잠금 경계를 검사한다.
                            sessionService.logout(login.refresh().getValue());
                            return null;
                        } finally {
                            jdbc.execute("SET SESSION innodb_lock_wait_timeout=50");
                        }
                    }));
                    // 같은 루트 행의 잠금 획득 실패를 실제 MySQL 오류로 검증한다.
                assertThatThrownBy(() -> attempt.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(org.springframework.dao.PessimisticLockingFailureException.class);
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
            me(login.access(), 200);
            logout(login.refresh(), 204);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void refreshFailureRollsBackNewSessionAndLeavesPreviousRefreshUsable() throws Exception {
        Login login = login();
        jdbc.execute("CREATE TRIGGER fail_refresh BEFORE INSERT ON auth_sessions FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test-only-refresh-failure'");
        try {
            refresh(login.refresh(), null, 500);
            assertThat(sessions.count()).isEqualTo(1);
            assertThat(sessions.findAll().getFirst().getRevokedAt()).isNull();
        } finally {
            jdbc.execute("DROP TRIGGER fail_refresh");
        }
        me(login.access(), 200);
        refresh(login.refresh(), null, 200);
    }

    @Test
    void logoutFailureRollsBackFamilyMarkerAndDoesNotPretendSuccess() throws Exception {
        Login login = login();
        jdbc.execute("CREATE TRIGGER fail_logout BEFORE UPDATE ON auth_sessions FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test-only-logout-failure'");
        try {
            logout(login.refresh(), 500);
            assertThat(sessions.findAll().getFirst().getFamilyRevokedAt()).isNull();
        } finally {
            jdbc.execute("DROP TRIGGER fail_logout");
        }
        me(login.access(), 200);
        logout(login.refresh(), 204);
        me(login.access(), 401);
    }

    @Test
    void v4ToV5UpgradePreservesExistingUserAndIsRepeatable() throws Exception {
        String schema = "sessions_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword()); var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/" + schema);
        Flyway.configure().dataSource(url, "root", mysql.getPassword()).locations("classpath:db/migration")
                .target("4").cleanDisabled(true).load().migrate();
        try (var connection = DriverManager.getConnection(url, "root", mysql.getPassword()); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO users(email,password_hash,name,role,status,rejection_reason,created_at,updated_at) "
                    + "VALUES ('preserve@example.com','preserve-hash','보존','STAFF','REJECTED','보존 사유',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        }
        var upgraded = Flyway.configure().dataSource(url, "root", mysql.getPassword()).locations("classpath:db/migration").cleanDisabled(true).load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
        upgraded.validate();
        try (var connection = DriverManager.getConnection(url, "root", mysql.getPassword()); var sql = connection.createStatement();
                var rows = sql.executeQuery("SELECT password_hash,rejection_reason FROM users WHERE email='preserve@example.com'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("preserve-hash");
            assertThat(rows.getString(2)).isEqualTo("보존 사유");
        }
    }

    private Login login() throws Exception {
        var csrf = csrf();
        return tokens(mvc.perform(post("/api/v1/auth/login").secure(true).cookie(csrf.cookie())
                        .header(ORIGIN, AUTH_ORIGIN).header(csrf.header(), csrf.token()).contentType(APPLICATION_JSON).content(credentials()))
                .andExpect(status().isOk()).andReturn());
    }

    private Csrf csrf() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").secure(true).header(ORIGIN, AUTH_ORIGIN))
                .andExpect(status().isOk()).andReturn();
        var json = JSON.readTree(result.getResponse().getContentAsByteArray());
        return new Csrf(new Cookie("XSRF-TOKEN", cookieValue(setCookie(result, "XSRF-TOKEN"))),
                json.get("headerName").asText(), json.get("token").asText());
    }

    private MvcResult refresh(Cookie refresh, String expiredAccess, int expected) throws Exception {
        var csrf = csrf();
        var request = post("/api/v1/auth/refresh").secure(true).cookie(csrf.cookie()).header(ORIGIN, AUTH_ORIGIN).header(csrf.header(), csrf.token());
        if (refresh != null) request.cookie(refresh);
        if (expiredAccess != null) request.header(AUTHORIZATION, "Bearer " + expiredAccess);
        return mvc.perform(request).andExpect(status().is(expected)).andReturn();
    }

    private MvcResult refreshWithCsrf(Cookie refresh, Csrf csrf) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").secure(true).cookie(csrf.cookie(), refresh)
                .header(ORIGIN, AUTH_ORIGIN).header(csrf.header(), csrf.token())).andReturn();
    }

    private MvcResult logout(Cookie refresh, int expected) throws Exception {
        var csrf = csrf();
        MvcResult result = logoutWithCsrf(refresh, csrf);
        assertThat(result.getResponse().getStatus()).isEqualTo(expected);
        return result;
    }

    private MvcResult logoutWithCsrf(Cookie refresh, Csrf csrf) throws Exception {
        var request = post("/api/v1/auth/logout").secure(true).cookie(csrf.cookie()).header(ORIGIN, AUTH_ORIGIN).header(csrf.header(), csrf.token());
        if (refresh != null) request.cookie(refresh);
        return mvc.perform(request).andReturn();
    }

    private void me(String token, int expected) throws Exception {
        mvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + token)).andExpect(status().is(expected));
    }

    private Login tokens(MvcResult result) {
        String token = JSON.readTree(result.getResponse().getContentAsByteArray()).get("accessToken").asText();
        return new Login(token, new Cookie(RefreshCookies.NAME, cookieValue(setCookie(result, RefreshCookies.NAME))),
                decoder.decode(token).getClaimAsString("sid"), result);
    }

    private String setCookie(MvcResult result, String name) {
        return result.getResponse().getHeaders(SET_COOKIE).stream().filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
    }

    private String cookieValue(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    private String code(MvcResult result) { return JSON.readTree(result.getResponse().getContentAsByteArray()).get("code").asText(); }
    private String credentials() { return JSON.writeValueAsString(Map.of("email", "refresh@example.com", "password", PASSWORD)); }
    record Csrf(Cookie cookie, String header, String token) { @Override public String toString() { return "Csrf[redacted]"; } }
    record Login(String access, Cookie refresh, String family, MvcResult result) { @Override public String toString() { return "Login[redacted]"; } }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        void set(Instant value) { now.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean @Primary MutableClock sessionClock() { return new MutableClock(); }
    }
}
