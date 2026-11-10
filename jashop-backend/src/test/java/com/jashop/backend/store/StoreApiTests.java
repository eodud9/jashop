package com.jashop.backend.store;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.jashop.backend.global.security.UserPrincipal;
import com.jashop.backend.store.api.dto.UpdateStoreRequest;
import com.jashop.backend.store.application.StoreService;
import com.jashop.backend.store.domain.Store;
import com.jashop.backend.store.infrastructure.StoreRepository;
import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.infrastructure.UserRepository;
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
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StoreApiTests.FixedClock.class)
@Testcontainers
class StoreApiTests {
    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    static final Instant NOW = Instant.parse("2026-11-10T01:02:03.123456Z");
    static final Instant CREATED = NOW.minusSeconds(60);
    @Autowired MockMvc mvc;
    @Autowired StoreRepository stores;
    @Autowired UserRepository users;
    @Autowired StoreService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    Long firstId;
    Long secondId;
    Long inactiveId;
    Long lastId;
    Long managerId;
    Long staffId;

    @BeforeEach
    void prepareIsolatedTestDatabase() {
        users.deleteAll();
        stores.deleteAll();
        firstId = saveStore("FIRST", "Z 매장", true);
        secondId = saveStore("SECOND", "B 매장", true);
        inactiveId = saveStore("INACTIVE", "비활성 매장", false);
        lastId = saveStore("LAST", "A 매장", true);
        managerId = users.saveAndFlush(User.createInitialManager("manager@example.com", "test-hash", "총괄", CREATED)).getId();
        staffId = users.saveAndFlush(User.createPending("staff@example.com", "test-hash", "직원", CREATED)).getId();
        jdbc.update("UPDATE users SET status = 'ACTIVE', home_store_id = ? WHERE id = ?", firstId, staffId);
    }

    @Test
    void anonymousAndSpoofedHeadersReturnJsonUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/stores").header("X-User-Id", managerId))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.timestamp").value(NOW.toString()))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
        mvc.perform(post("/api/v1/stores").with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"DENIED\",\"name\":\"매장\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(stores.count()).isEqualTo(4);
    }

    @Test
    void bootDefaultUserAndBasicOrBearerHeadersCannotBecomeDatabasePrincipal() throws Exception {
        mvc.perform(get("/api/v1/stores").with(user("manager").roles("MANAGER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/stores").with(httpBasic("manager@example.com", "test-hash")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/stores").header("Authorization", "Bearer forged-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void untrustedOrMissingDatabaseIdentityIsRejected() throws Exception {
        var unauthenticated = UsernamePasswordAuthenticationToken.unauthenticated(new UserPrincipal(managerId), null);
        mvc.perform(get("/api/v1/stores").with(authentication(unauthenticated)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/stores").with(actor(Long.MAX_VALUE)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void writesKeepCsrfProtectionAndReturnCommonError() throws Exception {
        mvc.perform(patch("/api/v1/stores/{id}/deactivate", firstId).with(actor(managerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        assertThat(stores.findById(firstId).orElseThrow().isActive()).isTrue();
    }

    @Test
    void defaultsFilterActiveAndSortOnlyById() throws Exception {
        mvc.perform(get("/api/v1/stores").with(actor(staffId)).param("sort", "name,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[0].id").value(firstId))
                .andExpect(jsonPath("$.content[1].id").value(secondId))
                .andExpect(jsonPath("$.content[2].id").value(lastId))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void pagesHaveNoOverlapAndOutOfRangePageIsEmpty() throws Exception {
        mvc.perform(get("/api/v1/stores").with(actor(staffId)).param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/v1/stores").with(actor(staffId)).param("page", "1").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(lastId))
                .andExpect(jsonPath("$.page").value(1));
        mvc.perform(get("/api/v1/stores").with(actor(staffId)).param("page", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void inactiveFilterAndInactiveDetailRemainAvailableToActiveUsers() throws Exception {
        mvc.perform(get("/api/v1/stores").with(actor(staffId)).param("active", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(inactiveId))
                .andExpect(jsonPath("$.content[0].active").value(false));
        mvc.perform(get("/api/v1/stores/{id}", inactiveId).with(actor(staffId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "page,bad", "size,0", "size,-1", "size,101", "size,bad", "active,bad"})
    void invalidListParametersReturnBadRequest(String parameter, String value) throws Exception {
        mvc.perform(get("/api/v1/stores").with(actor(staffId)).param(parameter, value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED", "INACTIVE"})
    void nonActiveManagerCannotReadOrWrite(String accountStatus) throws Exception {
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", accountStatus, managerId);
        mvc.perform(get("/api/v1/stores").with(actor(managerId)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_" + accountStatus));
        mvc.perform(patch("/api/v1/stores/{id}/deactivate", firstId).with(actor(managerId)).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_" + accountStatus));
        assertThat(stores.findById(firstId).orElseThrow().isActive()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"STAFF", "ASSISTANT_MANAGER"})
    void storeMutationsRequireDatabaseManagerRole(String role) throws Exception {
        jdbc.update("UPDATE users SET role = ? WHERE id = ?", role, staffId);
        mvc.perform(get("/api/v1/stores/{id}", secondId).with(actor(staffId))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/stores").with(actor(staffId)).with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"NEW\",\"name\":\"신규\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(patch("/api/v1/stores/{id}", firstId).with(actor(staffId)).with(csrf())
                        .header("X-User-Id", managerId).param("userId", managerId.toString())
                        .contentType(APPLICATION_JSON).content("{\"name\":\"변조\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/stores/{id}/deactivate", firstId).with(actor(staffId)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(stores.findById(firstId).orElseThrow().getName()).isEqualTo("Z 매장");
    }

    @Test
    void managerCreatesRenamesAndDeactivatesWithoutDeletingOrChangingCode() throws Exception {
        var result = mvc.perform(post("/api/v1/stores").with(actor(managerId)).with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"NEW\",\"name\":\"신규 매장\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").value(NOW.toString()))
                .andExpect(jsonPath("$.*", hasSize(6))).andReturn();
        Long id = stores.findByCode("NEW").orElseThrow().getId();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/stores/" + id);
        mvc.perform(patch("/api/v1/stores/{id}", id).with(actor(managerId)).with(csrf())
                        .contentType(APPLICATION_JSON).content("{\"name\":\"변경 매장\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("변경 매장"))
                .andExpect(jsonPath("$.code").value("NEW"));
        mvc.perform(patch("/api/v1/stores/{id}/deactivate", id).with(actor(managerId)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        assertThat(stores.findById(id)).isPresent();
        assertThat(stores.count()).isEqualTo(5);
    }

    @Test
    void repeatDeactivationPreservesExistingUpdatedAt() throws Exception {
        mvc.perform(patch("/api/v1/stores/{id}/deactivate", inactiveId).with(actor(managerId)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updatedAt").value(CREATED.toString()));
    }

    @Test
    void duplicateCodeUsesMysqlCollationAndReturnsConflict() throws Exception {
        mvc.perform(post("/api/v1/stores").with(actor(managerId)).with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"first\",\"name\":\"중복\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_STORE_CODE"));
        assertThat(stores.count()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"code\":\"\",\"name\":\"매장\"}",
            "{\"code\":\"N\",\"name\":\" \"}", "{\"code\":\"N\",\"name\":\"매장\",\"userId\":1}",
            "{\"code\":\"N\",\"name\":\"매장\",\"active\":false}",
            "{\"code\":\"N\",\"name\":\"매장\",\"role\":\"MANAGER\"}", "null", "{"})
    void invalidOrServerOwnedCreateFieldsAreRejected(String body) throws Exception {
        mvc.perform(post("/api/v1/stores").with(actor(managerId)).with(csrf())
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(stores.count()).isEqualTo(4);
    }

    @Test
    void lengthsAreBoundedAndUpdateCannotChangeCodeOrActive() throws Exception {
        mvc.perform(post("/api/v1/stores").with(actor(managerId)).with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + "C".repeat(41) + "\",\"name\":\"매장\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/stores/{id}", firstId).with(actor(managerId)).with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + "가".repeat(121) + "\"}"))
                .andExpect(status().isBadRequest());
        for (String body : List.of("{}", "{\"name\":\" \"}", "{\"name\":\"매장\",\"code\":\"NEW\"}",
                "{\"name\":\"매장\",\"active\":true}")) {
            mvc.perform(patch("/api/v1/stores/{id}", firstId).with(actor(managerId)).with(csrf())
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void missingStoresReturnNotFoundForReadsAndWrites() throws Exception {
        mvc.perform(get("/api/v1/stores/{id}", Long.MAX_VALUE).with(actor(staffId)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        mvc.perform(patch("/api/v1/stores/{id}", Long.MAX_VALUE).with(actor(managerId)).with(csrf())
                        .contentType(APPLICATION_JSON).content("{\"name\":\"매장\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/stores/{id}/deactivate", Long.MAX_VALUE).with(actor(managerId)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "bad"})
    void invalidStoreIdsAreBadRequest(String id) throws Exception {
        mvc.perform(get("/api/v1/stores/{id}", id).with(actor(staffId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reusedPrincipalReflectsDatabaseRoleRevocation() throws Exception {
        mvc.perform(get("/api/v1/stores").with(actor(managerId))).andExpect(status().isOk());
        jdbc.update("UPDATE users SET role = 'STAFF', home_store_id = ? WHERE id = ?", firstId, managerId);
        mvc.perform(patch("/api/v1/stores/{id}/deactivate", firstId).with(actor(managerId)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentDuplicateCreatesProduceCreatedAndConflict() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createTogether(start));
            var second = executor.submit(() -> createTogether(start));
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(stores.count()).isEqualTo(5);
    }

    @Test
    void concurrentRenameAndDeactivationPreserveBothChanges() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var rename = executor.submit(() -> {
                start.await();
                return service.rename(managerId, firstId, new UpdateStoreRequest("동시 변경"));
            });
            var deactivate = executor.submit(() -> {
                start.await();
                return service.deactivate(managerId, firstId);
            });
            start.countDown();
            rename.get(15, TimeUnit.SECONDS);
            deactivate.get(15, TimeUnit.SECONDS);
        }
        Store result = stores.findById(firstId).orElseThrow();
        assertThat(result.getName()).isEqualTo("동시 변경");
        assertThat(result.isActive()).isFalse();
    }

    @Test
    void sameStoreMutationWaitsForRowLockWhileOtherStoreMutationSucceeds() {
        new TransactionTemplate(transactions).executeWithoutResult(held -> {
            stores.findByIdForUpdate(firstId).orElseThrow();
            try (var executor = Executors.newFixedThreadPool(2)) {
                var same = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
                    try {
                        return service.rename(managerId, firstId, new UpdateStoreRequest("잠금 대기"));
                    } finally {
                        jdbc.execute("SET SESSION innodb_lock_wait_timeout = 50");
                    }
                }));
                var other = executor.submit(() -> service.rename(managerId, secondId, new UpdateStoreRequest("다른 매장")));
                assertThat(other.get(10, TimeUnit.SECONDS).name()).isEqualTo("다른 매장");
                assertThatThrownBy(() -> same.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(org.springframework.dao.PessimisticLockingFailureException.class);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
        assertThat(stores.findById(firstId).orElseThrow().getName()).isEqualTo("Z 매장");
    }

    private int createTogether(CountDownLatch start) throws Exception {
        start.await();
        return mvc.perform(post("/api/v1/stores").with(actor(managerId)).with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"RACE\",\"name\":\"경쟁\"}"))
                .andReturn().getResponse().getStatus();
    }

    private Long saveStore(String code, String name, boolean active) {
        Store store = Store.create(code, name, CREATED);
        if (!active) store.deactivate(CREATED);
        return stores.saveAndFlush(store).getId();
    }

    private RequestPostProcessor actor(Long id) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(new UserPrincipal(id), null, List.of()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean @Primary
        Clock storeClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
