package com.jashop.backend.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.jashop.backend.auth.api.dto.SignupRequest;
import com.jashop.backend.auth.application.SignupService;
import com.jashop.backend.user.domain.UserRole;
import com.jashop.backend.user.domain.UserStatus;
import com.jashop.backend.user.infrastructure.UserRepository;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc // 실제 운영 SecurityFilterChain도 통과한다.
@Import(SignupApiTests.FixedClockConfig.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class SignupApiTests {
    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("jashop_signup_test")
            .withUsername("jashop_test")
            .withPassword("test-only-password")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

    private static final Instant NOW = Instant.parse("2026-11-09T01:02:03.123456Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String RAW_PASSWORD = "test-only-signup-password";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired SignupService service;

    @Test
    void publicSignupCreatesPendingUserWithoutExposingCredentials(CapturedOutput output) throws Exception {
        String email = uniqueEmail();
        var result = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(body(" " + email.toUpperCase(java.util.Locale.ROOT) + " ", RAW_PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*", hasSize(6)))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("직원"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.homeStoreId").isEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        var user = users.findByEmail(email).orElseThrow();
        assertThat(user.getPasswordHash()).startsWith("{bcrypt}$2a$12$").isNotEqualTo(RAW_PASSWORD);
        assertThat(encoder.matches(RAW_PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(encoder.matches("wrong-password", user.getPasswordHash())).isFalse();
        assertThat(user.getRole()).isEqualTo(UserRole.STAFF);
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(user.getApprovedBy()).isNull();
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(RAW_PASSWORD, user.getPasswordHash());
        assertThat(output.getAll()).doesNotContain(RAW_PASSWORD, user.getPasswordHash());
    }

    @Test
    void duplicateNormalizedEmailReturnsConflictAndKeepsOneUser() throws Exception {
        String email = uniqueEmail();
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(body(email, RAW_PASSWORD))).andExpect(status().isCreated());
        long before = users.count();
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(body(" " + email.toUpperCase(java.util.Locale.ROOT) + " ", RAW_PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
        assertThat(users.count()).isEqualTo(before);
    }

    @Test
    void concurrentSignupCreatesExactlyOneUser() throws Exception {
        String email = uniqueEmail();
        String payload = body(email, RAW_PASSWORD);
        long before = users.count();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<Integer>) () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                var response = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(payload)).andReturn().getResponse();
                if (response.getStatus() == 409) {
                    assertThat(JSON.readTree(response.getContentAsString()).get("code").asString())
                            .isEqualTo("DUPLICATE_EMAIL");
                }
                return response.getStatus();
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        } finally {
            start.countDown();
        }
        assertThat(users.count()).isEqualTo(before + 1);
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void invalidFieldsReturn400WithoutSavingOrEchoingInput(String field, Object value, CapturedOutput output) throws Exception {
        Map<String, Object> payload = validPayload(uniqueEmail(), RAW_PASSWORD);
        payload.put(field, value);
        long before = users.count();
        var result = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(field))
                .andReturn();
        assertThat(users.count()).isEqualTo(before);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(RAW_PASSWORD, "rejectedValue");
        assertThat(output.getAll()).doesNotContain(RAW_PASSWORD);
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("name", ""), Arguments.of("name", " "),
                Arguments.of("name", "이".repeat(101)), Arguments.of("name", null),
                Arguments.of("email", "invalid-email"), Arguments.of("email", null),
                Arguments.of("email", "a".repeat(255) + "@example.com"),
                Arguments.of("password", ""), Arguments.of("password", "short"),
                Arguments.of("password", null), Arguments.of("password", " ".repeat(8)),
                Arguments.of("password", "x".repeat(73)), Arguments.of("password", "가".repeat(25)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "status", "homeStoreId", "approvedBy", "passwordHash"})
    void rejectsUnknownAndPrivilegedFields(String field) throws Exception {
        var payload = validPayload(uniqueEmail(), RAW_PASSWORD);
        payload.put(field, field.equals("role") ? "MANAGER" : "unexpected");
        long before = users.count();
        var result = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn();
        assertThat(users.count()).isEqualTo(before);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(RAW_PASSWORD, "unexpected");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", ""})
    void rejectsMalformedOrMissingBody(String payload) throws Exception {
        long before = users.count();
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(users.count()).isEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("boundaryPasswords")
    void acceptsSupportedPasswordBoundariesWithoutTrimming(String password) throws Exception {
        String email = uniqueEmail();
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body(email, password)))
                .andExpect(status().isCreated());
        assertThat(encoder.matches(password, users.findByEmail(email).orElseThrow().getPasswordHash())).isTrue();
    }

    static Stream<String> boundaryPasswords() {
        return Stream.of("x".repeat(8), "x".repeat(72), "가".repeat(24), " password ");
    }

    @Test
    void samePasswordGetsDifferentSaltedHashes() throws Exception {
        String first = uniqueEmail();
        String second = uniqueEmail();
        for (String email : java.util.List.of(first, second)) {
            mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                    .content(body(email, RAW_PASSWORD))).andExpect(status().isCreated());
        }
        String hash1 = users.findByEmail(first).orElseThrow().getPasswordHash();
        String hash2 = users.findByEmail(second).orElseThrow().getPasswordHash();
        assertThat(hash1).isNotEqualTo(hash2);
        assertThat(encoder.matches(RAW_PASSWORD, hash1)).isTrue();
        assertThat(encoder.matches(RAW_PASSWORD, hash2)).isTrue();
    }

    @Test
    void signupPermissionDoesNotOpenOtherRoutesOrDisableTheirCsrf() throws Exception {
        mvc.perform(get("/api/v1/users/me").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsNonJsonContent() throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.TEXT_PLAIN).content("not-json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void serviceProxyAlsoValidatesRequests() {
        long before = users.count();
        assertThatThrownBy(() -> service.signup(new SignupRequest("직원", "invalid", "short")))
                .isInstanceOf(ConstraintViolationException.class);
        assertThat(users.count()).isEqualTo(before);
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }

    private static Map<String, Object> validPayload(String email, String password) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "직원");
        payload.put("email", email);
        payload.put("password", password);
        return payload;
    }

    private static String body(String email, String password) {
        return JSON.writeValueAsString(validPayload(email, password));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock signupTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
