package com.jashop.backend.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.crypto.spec.SecretKeySpec;

import com.jashop.backend.auth.api.dto.LoginRequest;
import com.jashop.backend.auth.application.LoginService;
import com.jashop.backend.global.security.UserPrincipal;
import com.jashop.backend.store.domain.Store;
import com.jashop.backend.store.infrastructure.StoreRepository;
import com.jashop.backend.support.JwtTestSupport;
import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.domain.UserRole;
import com.jashop.backend.user.infrastructure.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpHeaders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(LoginApiTests.FixedClock.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class LoginApiTests extends JwtTestSupport {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");
    static final Instant NOW = Instant.parse("2026-11-11T01:02:03Z");
    static final String PASSWORD = "test-only-login-password";
    static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired StoreRepository stores;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired LoginService loginService;
    String hash;
    @Autowired com.jashop.backend.auth.session.application.AuthSessionService sessions;
    String managerFamily;
    String staffFamily;
    Long managerId;
    Long staffId;
    Long storeId;

    @BeforeAll
    void encodeFixturePassword() {
        hash = passwords.encode(PASSWORD);
    }

    @BeforeEach
    void prepareIsolatedDatabase() {
        jdbc.update("UPDATE users SET approved_by = NULL");
        jdbc.update("UPDATE auth_sessions SET replaced_by_id = NULL");
        jdbc.update("DELETE FROM auth_sessions");
        users.deleteAll();
        stores.deleteAll();
        storeId = stores.saveAndFlush(Store.create("LOGIN", "로그인 매장", NOW)).getId();
        managerId = users.saveAndFlush(User.createInitialManager("manager@example.com", hash, "총괄", NOW)).getId();
        User staff = User.createPending("staff@example.com", hash, "직원", NOW);
        staff.approve(UserRole.STAFF, storeId, managerId, NOW);
        staffId = users.saveAndFlush(staff).getId();
        managerFamily = sessions.open(managerId).familyId();
        staffFamily = sessions.open(staffId).familyId();
    }

    @Test
    void loginNormalizesEmailAndReturnsAccessWithSecureRefreshCookie(CapturedOutput output) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").with(cookieAuthCsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(" MANAGER@EXAMPLE.COM ", PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900)).andExpect(jsonPath("$.user.id").value(managerId))
                .andExpect(jsonPath("$.user.role").value("MANAGER"))
                .andExpect(jsonPath("$.user.email").value("manager@example.com"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(header().string(CACHE_CONTROL, "no-store"))
                .andExpect(header().string(SET_COOKIE, org.hamcrest.Matchers.containsString("HttpOnly"))).andReturn();
        String response = result.getResponse().getContentAsString();
        String token = JSON.readTree(response).get("accessToken").asText();
        Jwt jwt = jwtDecoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo(managerId.toString());
        assertThat(jwt.getIssuer().toString()).isEqualTo("https://jashop.local");
        assertThat(jwt.getAudience()).containsExactly("jashop-api");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(jwt.getClaimAsString("token_use")).isEqualTo("access");
        assertThat(jwt.getClaims()).doesNotContainKeys("role", "password", "email", "passwordHash");
        assertThat(response).doesNotContain(PASSWORD, hash, JWT_SECRET, "refreshToken");
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(output.getAll()).doesNotContain(PASSWORD, hash, JWT_SECRET, token);
        assertThat(new LoginRequest("manager@example.com", PASSWORD).toString()).doesNotContain(PASSWORD, "manager@example.com");
        assertThat(loginService.login(new LoginRequest("manager@example.com", PASSWORD)).toString()).doesNotContain(PASSWORD, "manager@example.com");
    }

    @Test
    void accessJwtAuthenticatesCurrentUserAndStoreWithoutSession() throws Exception {
        String token = login("staff@example.com", PASSWORD);
        var me = mvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(staffId))
                .andExpect(jsonPath("$.role").value("STAFF")).andExpect(jsonPath("$.homeStoreId").value(storeId))
                .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(header().doesNotExist(SET_COOKIE)).andReturn();
        assertThat(me.getRequest().getSession(false)).isNull();
        mvc.perform(get("/api/v1/stores").header(AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void realManagerTokenCanCreateStoreApproveAndRejectWithoutCsrf() throws Exception {
        String token = login("manager@example.com", PASSWORD);
        mvc.perform(post("/api/v1/stores").header(AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"JWT\",\"name\":\"JWT 매장\"}"))
                .andExpect(status().isCreated());
        Long pending = users.saveAndFlush(User.createPending("pending@example.com", hash, "대기", NOW)).getId();
        mvc.perform(get("/api/v1/admin/users/pending").header(AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(post("/api/v1/admin/users/{id}/approve", pending).header(AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"STAFF\",\"homeStoreId\":" + storeId + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.approvedBy").value(managerId));
        String approvedToken = login("pending@example.com", PASSWORD);
        mvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + approvedToken)).andExpect(status().isOk());
        Long rejected = users.saveAndFlush(User.createPending("reject@example.com", hash, "거절", NOW)).getId();
        mvc.perform(post("/api/v1/admin/users/{id}/reject", rejected).header(AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login").with(cookieAuthCsrf()).contentType(MediaType.APPLICATION_JSON).content(body("reject@example.com", PASSWORD)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_REJECTED"));
    }

    @Test
    void missingEmailAndWrongPasswordUseSameUnauthorizedResponse() throws Exception {
        for (String email : List.of("missing@example.com", "manager@example.com")) {
            mvc.perform(post("/api/v1/auth/login").with(cookieAuthCsrf()).contentType(MediaType.APPLICATION_JSON).content(body(email, "wrong-password")))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.accessToken").doesNotExist()).andExpect(header().doesNotExist(SET_COOKIE));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED", "INACTIVE"})
    void nonActiveAccountsCannotLoginAndWrongPasswordDoesNotDiscloseStatus(String state) throws Exception {
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", state, staffId);
        mvc.perform(post("/api/v1/auth/login").with(cookieAuthCsrf()).contentType(MediaType.APPLICATION_JSON).content(body("staff@example.com", PASSWORD)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_" + state))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        mvc.perform(post("/api/v1/auth/login").with(cookieAuthCsrf()).contentType(MediaType.APPLICATION_JSON).content(body("staff@example.com", "wrong-password")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED", "INACTIVE"})
    void existingTokenCannotBypassCurrentAccountState(String state) throws Exception {
        String token = login("manager@example.com", PASSWORD);
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", state, managerId);
        for (String path : List.of("/api/v1/users/me", "/api/v1/stores", "/api/v1/admin/users/pending")) {
            mvc.perform(get(path).header(AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_" + state));
        }
    }

    @Test
    void roleRevocationIsEffectiveWithoutReissuingTokenAndClaimsCannotGrantRole() throws Exception {
        String token = login("manager@example.com", PASSWORD);
        jdbc.update("UPDATE users SET role='STAFF',home_store_id=? WHERE id=?", storeId, managerId);
        mvc.perform(get("/api/v1/admin/users/pending").header(AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        String claimedManager = signed(claims -> { claims.put("sub", staffId.toString()); claims.put("role", "MANAGER"); claims.put("sid", staffFamily); });
        mvc.perform(post("/api/v1/stores").header(AUTHORIZATION, "Bearer " + claimedManager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"FORGED\",\"name\":\"위조\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void noTokenCookieQueryBasicOrOldHttpSessionCanAuthenticate() throws Exception {
        String token = login("manager@example.com", PASSWORD);
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(new UserPrincipal(managerId), null, List.of())));
        mvc.perform(get("/api/v1/users/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").cookie(new Cookie("access_token", token))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").param("access_token", token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").with(httpBasic("manager@example.com", PASSWORD))).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "missing-issuer", "missing-audience", "missing-exp", "missing-iat",
            "missing-sub", "zero-sub", "negative-sub", "overflow-sub", "text-sub", "future-iat", "future-nbf",
            "wrong-use", "missing-use"})
    void invalidClaimsAreRejected(String scenario) throws Exception {
        String token = signed(claims -> {
            switch (scenario) {
                case "issuer" -> claims.put("iss", "other");
                case "audience" -> claims.put("aud", List.of("other"));
                case "missing-issuer" -> claims.remove("iss");
                case "missing-audience" -> claims.remove("aud");
                case "missing-exp" -> claims.remove("exp");
                case "missing-iat" -> claims.remove("iat");
                case "missing-sub" -> claims.remove("sub");
                case "zero-sub" -> claims.put("sub", "0");
                case "negative-sub" -> claims.put("sub", "-1");
                case "overflow-sub" -> claims.put("sub", "9223372036854775808");
                case "text-sub" -> claims.put("sub", "manager");
                case "future-iat" -> claims.put("iat", NOW.plusSeconds(1));
                case "future-nbf" -> claims.put("nbf", NOW.plusSeconds(1));
                case "wrong-use" -> claims.put("token_use", "refresh");
                case "missing-use" -> claims.remove("token_use");
                default -> throw new AssertionError(scenario);
            }
        });
        mvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"));
    }

    @Test
    void expirationBeforeIssuedAtIsRejectedEvenForSignedToken() throws Exception {
        // Spring의 발급기는 이 조합을 거부하므로 검증 테스트용 잘못된 토큰을 하위 JOSE API로 만든다.
        var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("https://jashop.local")
                .audience("jashop-api").subject(managerId.toString()).issueTime(java.util.Date.from(NOW))
                .expirationTime(java.util.Date.from(NOW.minusSeconds(1))).claim("token_use", "access").claim("sid", managerFamily).build();
        var jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(
                com.nimbusds.jose.JWSAlgorithm.HS256).type(com.nimbusds.jose.JOSEObjectType.JWT).build(), claims);
        jwt.sign(new com.nimbusds.jose.crypto.MACSigner(Base64.getDecoder().decode(JWT_SECRET)));
        mvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + jwt.serialize()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokensIncludingExactBoundaryUseCommonExpiredError() throws Exception {
        for (Instant expiration : List.of(NOW, NOW.minusSeconds(1))) {
            String token = signed(claims -> { claims.put("iat", NOW.minusSeconds(900)); claims.put("exp", expiration); });
            mvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
                    .andExpect(jsonPath("$.timestamp").value(NOW.toString()))
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
        }
    }

    @Test
    void invalidSignatureMalformedAndUnsignedTokensAreRejected() throws Exception {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        var wrongEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(key, "HmacSHA256")));
        String wrongSignature = encode(wrongEncoder, claims -> {});
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (String token : List.of(wrongSignature, "malformed", header + ".e30.", "a.b.c")) {
            mvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Test
    void validTokenForDeletedUserIsUnauthorized() throws Exception {
        String token = signed(claims -> claims.put("sub", Long.toString(Long.MAX_VALUE)));
        mvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"email\":\"bad\",\"password\":\"password\"}",
            "{\"email\":\"manager@example.com\",\"password\":\" \"}",
            "{\"email\":\"manager@example.com\",\"password\":\"password\",\"role\":\"MANAGER\"}", "null", "{"})
    void invalidLoginBodyIsRejected(String body) throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(cookieAuthCsrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void loginEnforcesBcryptByteBoundaryAndPreservesPasswordWhitespace() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(cookieAuthCsrf()).contentType(MediaType.APPLICATION_JSON).content(body("manager@example.com", "가".repeat(25))))
                .andExpect(status().isBadRequest());
        String padded = " padded-password ";
        users.saveAndFlush(User.createInitialManager("padded@example.com", passwords.encode(padded), "공백", NOW));
        assertThat(login("padded@example.com", padded)).isNotBlank();
        mvc.perform(post("/api/v1/auth/login").with(cookieAuthCsrf()).contentType(MediaType.APPLICATION_JSON).content(body("padded@example.com", padded.strip())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsAllowsOnlyConfiguredOriginAndBearerHeaders() throws Exception {
        mvc.perform(options("/api/v1/stores").header(ORIGIN, "http://localhost:5173")
                        .header(ACCESS_CONTROL_REQUEST_METHOD, "PATCH").header(ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk()).andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        mvc.perform(options("/api/v1/stores").header(ORIGIN, "https://untrusted.example")
                        .header(ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshAndLogoutRejectRequestsWithoutOriginAndCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isForbidden());
        mvc.perform(get("/login")).andExpect(status().isUnauthorized());
    }

    private String login(String email, String password) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").with(cookieAuthCsrf()).contentType(MediaType.APPLICATION_JSON).content(body(email, password)))
                .andExpect(status().isOk()).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String body(String email, String password) {
        return JSON.writeValueAsString(Map.of("email", email, "password", password));
    }

    private String signed(Consumer<Map<String, Object>> changes) {
        return encode(jwtEncoder, changes);
    }

    private String encode(JwtEncoder encoder, Consumer<Map<String, Object>> changes) {
        var claims = JwtClaimsSet.builder().issuer("https://jashop.local").audience(List.of("jashop-api"))
                .subject(managerId.toString()).issuedAt(NOW).expiresAt(NOW.plusSeconds(900))
                .claim("token_use", "access").claim("sid", managerFamily).claims(changes).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean @Primary
        Clock loginClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
