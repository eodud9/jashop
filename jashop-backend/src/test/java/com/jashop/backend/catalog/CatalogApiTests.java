package com.jashop.backend.catalog;

import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.jashop.backend.auth.session.application.AuthSessionService;
import com.jashop.backend.catalog.api.dto.*;
import com.jashop.backend.catalog.application.CatalogService;
import com.jashop.backend.catalog.infrastructure.*;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.store.domain.Store;
import com.jashop.backend.store.infrastructure.StoreRepository;
import com.jashop.backend.support.JwtTestSupport;
import com.jashop.backend.user.domain.*;
import com.jashop.backend.user.infrastructure.UserRepository;
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

import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(CatalogApiTests.TimeConfig.class)
@Testcontainers
class CatalogApiTests extends JwtTestSupport {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4").withCommand("--log-bin-trust-function-creators=1");
    static final Instant NOW = Instant.parse("2026-11-20T01:00:00.123456Z");
    static final String BASE = "/api/v1/products";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductRepository products;
    @Autowired ProductVariantRepository variants;
    @Autowired UserRepository users;
    @Autowired StoreRepository stores;
    @Autowired CatalogService service;
    @Autowired AuthSessionService sessions;
    @Autowired PlatformTransactionManager transactions;
    Long manager, staff, assistant, pending;

    @BeforeEach
    void prepare() {
        variants.deleteAll(); products.deleteAll();
        jdbc.update("UPDATE auth_sessions SET replaced_by_id=NULL");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE users SET approved_by=NULL");
        users.deleteAll(); stores.deleteAll();
        Long store = stores.saveAndFlush(Store.create("CATALOG", "매장", NOW)).getId();
        manager = users.saveAndFlush(User.createInitialManager("manager@example.com", "hash", "총괄", NOW)).getId();
        staff = employee("staff", UserRole.STAFF, store);
        assistant = employee("assistant", UserRole.ASSISTANT_MANAGER, store);
        pending = users.saveAndFlush(User.createPending("pending@example.com", "hash", "대기", NOW)).getId();
    }

    @Test
    void createsProductAndAttributeLessFirstVariantAtomicallyWithNormalizationAndUtc() throws Exception {
        call(post(BASE).contentType(APPLICATION_JSON).content("""
                {"name":" 부채 ","category":" 응원용품 ","description":"  ",
                 "firstVariant":{"sku":" fan-001 ","size":" ","color":null}}
                """), manager).andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.product.name").value("부채"))
                .andExpect(jsonPath("$.product.category").value("응원용품"))
                .andExpect(jsonPath("$.product.description").isEmpty())
                .andExpect(jsonPath("$.product.createdAt").value(NOW.toString()))
                .andExpect(jsonPath("$.firstVariant.sku").value("FAN-001"))
                .andExpect(jsonPath("$.firstVariant.size").isEmpty())
                .andExpect(jsonPath("$.firstVariant.color").isEmpty())
                .andExpect(jsonPath("$.firstVariant.effectiveActive").value(true));
        assertThat(products.count()).isEqualTo(1); assertThat(variants.count()).isEqualTo(1);
        var variant = variants.findAll().getFirst();
        assertThat(variant.getProductId()).isEqualTo(products.findAll().getFirst().getId());
        assertThat(jdbc.queryForObject("SELECT created_at FROM products", LocalDateTime.class)).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":\"부채\"}", "{\"name\":\"부채\",\"firstVariant\":null}",
            "{\"name\":\"부채\",\"firstVariant\":{}}", "{\"name\":\"  \",\"firstVariant\":{\"sku\":\"FAN\"}}",
            "{\"name\":\"부채\",\"firstVariant\":{\"sku\":\"  \"}}",
            "{\"name\":\"부채\",\"active\":false,\"firstVariant\":{\"sku\":\"FAN\"}}",
            "{\"name\":\"부채\",\"firstVariant\":{\"sku\":\"FAN\",\"productId\":1}}"})
    void rejectsInvalidCreationWithoutOrphanProduct(String body) throws Exception {
        call(post(BASE).contentType(APPLICATION_JSON).content(body), manager).andExpect(status().isBadRequest());
        assertThat(products.count()).isZero(); assertThat(variants.count()).isZero();
    }

    @Test
    void enforcesLengthAfterUppercasingAndNestedValidation() throws Exception {
        for (String body : List.of(
                "{\"name\":\"" + "a".repeat(201) + "\",\"firstVariant\":{\"sku\":\"FAN\"}}",
                "{\"name\":\"부채\",\"firstVariant\":{\"sku\":\"" + "ß".repeat(51) + "\"}}",
                "{\"name\":\"부채\",\"firstVariant\":{\"sku\":\"FAN\",\"size\":\"" + "a".repeat(51) + "\"}}")) {
            call(post(BASE).contentType(APPLICATION_JSON).content(body), manager).andExpect(status().isBadRequest());
        }
        assertThat(products.count()).isZero();
    }

    @Test
    void childInsertFailureRollsBackAlreadyInsertedParent() {
        jdbc.execute("CREATE TRIGGER fail_variant BEFORE INSERT ON product_variants FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test-only failure'");
        try { assertThatThrownBy(() -> create("FAN")).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("DROP TRIGGER fail_variant"); }
        assertThat(products.count()).isZero(); assertThat(variants.count()).isZero();
    }

    @Test
    void staffAndAssistantCanReadButCannotWriteAndInactiveReadIsManagerOnly() throws Exception {
        var created = create("FAN");
        for (Long actor : List.of(staff, assistant)) {
            call(get(BASE), actor).andExpect(status().isOk());
            call(get(BASE + "/" + created.product().id()), actor).andExpect(status().isOk());
            call(get(variantPath(created)), actor).andExpect(status().isOk());
            call(get(BASE).param("includeInactive", "true"), actor).andExpect(status().isForbidden());
            call(get(variantPath(created)).param("includeInactive", "true"), actor).andExpect(status().isForbidden());
            call(post(BASE).contentType(APPLICATION_JSON).content(body("SECOND")), actor).andExpect(status().isForbidden());
            call(patch(BASE + "/" + created.product().id()).contentType(APPLICATION_JSON).content("{\"name\":\"변경\",\"active\":false}"), actor).andExpect(status().isForbidden());
            call(post(BASE + "/" + created.product().id() + "/variants").contentType(APPLICATION_JSON).content("{\"sku\":\"SECOND\"}"), actor).andExpect(status().isForbidden());
            call(patch(variantPath(created)).contentType(APPLICATION_JSON).content("{\"active\":false}"), actor).andExpect(status().isForbidden());
        }
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        assertThatThrownBy(() -> service.list(pending, null, false, 0, 20)).isInstanceOf(BusinessException.class);
    }

    @Test
    void normalizedDuplicateSkuIncludingInactiveSkuCannotBeReused() throws Exception {
        var first = create("fan-001");
        service.updateVariant(manager, first.firstVariant().id(), new UpdateVariantRequest(null, null, false));
        call(post(BASE).contentType(APPLICATION_JSON).content(body(" FAN-001 ")), manager)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
        call(post(BASE + "/" + first.product().id() + "/variants").contentType(APPLICATION_JSON).content("{\"sku\":\"Fan-001\"}"), manager)
                .andExpect(status().isConflict());
        assertThat(products.count()).isEqualTo(1); assertThat(variants.count()).isEqualTo(1);
    }

    @Test
    void supportsSizeOnlyColorOnlyAndMultipleAttributeLessSkus() {
        Long id = create("BASE").product().id();
        assertThat(service.addVariant(manager, id, new CreateVariantRequest("SIZE", " M ", null)).size()).isEqualTo("M");
        assertThat(service.addVariant(manager, id, new CreateVariantRequest("COLOR", null, " 빨강 ")).color()).isEqualTo("빨강");
        assertThat(service.addVariant(manager, id, new CreateVariantRequest("OTHER", null, null)).id()).isNotNull();
        assertThat(service.listVariants(staff, id, false, 0, 20).totalElements()).isEqualTo(4);
    }

    @Test
    void productDeactivationPreservesOwnVariantStateAndReactivationRestoresAvailability() throws Exception {
        var first = create("FIRST"); Long id = first.product().id();
        var disabled = service.addVariant(manager, id, new CreateVariantRequest("DISABLED", null, null));
        service.updateVariant(manager, disabled.id(), new UpdateVariantRequest(null, null, false));
        service.update(manager, id, new UpdateProductRequest("부채", null, null, false));
        call(get(BASE + "/" + id), staff).andExpect(status().isNotFound());
        call(get(variantPath(first)), staff).andExpect(status().isNotFound());
        call(get(BASE), staff).andExpect(jsonPath("$.totalElements").value(0));
        call(get(variantPath(first)).param("includeInactive", "true"), manager)
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.effectiveActive").value(false));
        call(get(BASE + "/" + id).param("includeInactive", "true"), manager)
                .andExpect(jsonPath("$.variants.totalElements").value(2));
        call(post(BASE + "/" + id + "/variants").contentType(APPLICATION_JSON).content("{\"sku\":\"NEW\"}"), manager)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE"));
        service.update(manager, id, new UpdateProductRequest("부채", null, null, true));
        assertThat(service.getVariant(staff, first.firstVariant().id(), false).effectiveActive()).isTrue();
        call(get("/api/v1/product-variants/" + disabled.id()), staff).andExpect(status().isNotFound());
        assertThat(service.getVariant(manager, disabled.id(), true).active()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"sku\":\"CHANGED\",\"active\":true}", "{\"productId\":2,\"active\":true}", "{}", "{\"active\":null}"})
    void rejectsSkuOrParentChangesAndMissingActive(String body) throws Exception {
        var first = create("FAN");
        call(patch(variantPath(first)).contentType(APPLICATION_JSON).content(body), manager).andExpect(status().isBadRequest());
        assertThat(variants.findById(first.firstVariant().id()).orElseThrow().getSku()).isEqualTo("FAN");
    }

    @Test
    void patchReplacesEditableFieldsAndClearsOmittedOptionalAttributes() throws Exception {
        var first = service.create(manager, new CreateProductRequest("유니폼", "의류", "설명", new CreateVariantRequest("SHIRT", "M", "파랑")));
        call(patch(BASE + "/" + first.product().id()).contentType(APPLICATION_JSON).content("{\"name\":\" 새 이름 \",\"active\":true}"), manager)
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("새 이름"))
                .andExpect(jsonPath("$.category").isEmpty()).andExpect(jsonPath("$.description").isEmpty());
        call(patch(variantPath(first)).contentType(APPLICATION_JSON).content("{\"color\":\" 빨강 \",\"active\":false}"), manager)
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").isEmpty()).andExpect(jsonPath("$.color").value("빨강"))
                .andExpect(jsonPath("$.sku").value("SHIRT")).andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void listAndDetailPageByIdWithoutUnboundedNestedVariants() throws Exception {
        var first = create("FIRST"); var second = create("SECOND");
        for (int i = 0; i < 24; i++) service.addVariant(manager, first.product().id(), new CreateVariantRequest("PAGE-" + i, null, null));
        call(get(BASE).param("page", "1").param("size", "1"), staff)
                .andExpect(jsonPath("$.content[0].id").value(second.product().id()))
                .andExpect(jsonPath("$.content[0].variants").doesNotExist()).andExpect(jsonPath("$.content[0].description").doesNotExist());
        call(get(BASE + "/" + first.product().id()), staff)
                .andExpect(jsonPath("$.variants.content.length()").value(20)).andExpect(jsonPath("$.variants.totalElements").value(25));
        call(get(BASE + "/" + first.product().id() + "/variants").param("page", "1"), staff)
                .andExpect(jsonPath("$.content.length()").value(5)).andExpect(jsonPath("$.totalPages").value(2));
        var page = service.listVariants(staff, first.product().id(), false, 0, 100);
        assertThat(page.content().stream().map(ProductVariantResponse::id).toList()).isSorted();
    }

    @Test
    void searchesNameSkuSizeColorAndDoesNotDuplicateProductMatches() throws Exception {
        var first = service.create(manager, new CreateProductRequest("홈 유니폼", null, null, new CreateVariantRequest("HOME-M", "Medium", "화이트")));
        service.addVariant(manager, first.product().id(), new CreateVariantRequest("HOME-L", "Large", "화이트"));
        create("UNRELATED");
        for (String term : List.of("홈", "home", "medium", "화이트")) {
            call(get(BASE).param("keyword", term), staff).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(first.product().id()));
        }
        service.updateVariant(manager, first.firstVariant().id(), new UpdateVariantRequest("Medium", "화이트", false));
        call(get(BASE).param("keyword", "HOME-M"), staff).andExpect(jsonPath("$.totalElements").value(0));
        call(get(BASE).param("keyword", "HOME-M").param("includeInactive", "true"), manager).andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "!"})
    void searchTreatsSqlWildcardAndEscapeCharactersLiterally(String term) throws Exception {
        service.create(manager, new CreateProductRequest("50%_! 부채", null, null, new CreateVariantRequest("SPECIAL%_!", null, null)));
        create("ORDINARY");
        call(get(BASE).param("keyword", term), staff).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=2147483647&size=100"})
    void rejectsInvalidPages(String query) throws Exception {
        call(get(BASE + "?" + query), staff).andExpect(status().isBadRequest());
    }

    @Test
    void missingAndNonpositiveIdsReturnAppropriateErrors() throws Exception {
        call(get(BASE + "/999999"), staff).andExpect(status().isNotFound());
        call(get("/api/v1/product-variants/999999"), staff).andExpect(status().isNotFound());
        call(get(BASE + "/0"), staff).andExpect(status().isBadRequest());
        call(get(BASE + "/1/variants").param("size", "101"), staff).andExpect(status().isNotFound());
    }

    @Test
    void concurrentSameSkuCreationHasOneWinnerAndNoOrphanProduct() throws Exception {
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> task = () -> { gate.await(); return attempt(() -> create("RACE")); };
            var a = pool.submit(task); var b = pool.submit(task); gate.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK", "DUPLICATE_SKU");
        }
        assertThat(products.count()).isEqualTo(1); assertThat(variants.count()).isEqualTo(1);
    }

    @Test
    void addWaitsForParentLockAndThenSeesCommittedDeactivation() throws Exception {
        var first = create("FIRST"); Long id = first.product().id();
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var started = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> {
                var product = products.findByIdForUpdate(id).orElseThrow();
                product.change("부채", null, null, false, NOW); locked.countDown(); await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var waiter = pool.submit(() -> { started.countDown(); return attempt(() -> service.addVariant(manager, id, new CreateVariantRequest("BLOCKED", null, null))); });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            try { assertThatThrownBy(() -> waiter.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            holder.get(10, TimeUnit.SECONDS);
            assertThat(waiter.get(10, TimeUnit.SECONDS)).isEqualTo("PRODUCT_INACTIVE");
        } finally { release.countDown(); }
        assertThat(variants.count()).isEqualTo(1);
    }

    @Test
    void uniqueConstraintRaceRollsBackLosingParentAfterPrecheck() throws Exception {
        var inserted = new CountDownLatch(1); var release = new CountDownLatch(1); var started = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                // 미커밋 SKU는 다른 트랜잭션의 사전 조회에는 보이지 않고 UNIQUE 검사에서는 기다리게 한다.
                create("CONTENDED"); inserted.countDown(); await(release);
            }));
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            var loser = pool.submit(() -> { started.countDown(); return attempt(() -> create("CONTENDED")); });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            try { assertThatThrownBy(() -> loser.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            holder.get(10, TimeUnit.SECONDS);
            assertThat(loser.get(10, TimeUnit.SECONDS)).isEqualTo("DUPLICATE_SKU");
        } finally { release.countDown(); }
        assertThat(products.count()).isEqualTo(1); assertThat(variants.count()).isEqualTo(1);
    }

    @Test
    void databaseProtectsUniqueSkuAndParentReferenceWithoutFoldingAccents() {
        var created = create("CAFE"); Long id = created.product().id();
        assertThat(service.addVariant(manager, id, new CreateVariantRequest("CAFÉ", null, null)).sku()).isEqualTo("CAFÉ");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO product_variants(product_id,sku,created_at,updated_at) VALUES (?,'CAFE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO product_variants(product_id,sku,created_at,updated_at) VALUES (999999,'ORPHAN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void v9UpgradePreservesExistingUserAndReexecutionPreservesCatalog() throws Exception {
        String schema = "catalog_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword()); var sql = c.createStatement()) {
            sql.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/" + schema);
        Flyway.configure().dataSource(url, "root", mysql.getPassword()).locations("classpath:db/migration").target("9").load().migrate();
        try (var c = DriverManager.getConnection(url, "root", mysql.getPassword()); var sql = c.createStatement()) {
            sql.executeUpdate("INSERT INTO users(email,password_hash,name,role,status,created_at,updated_at) VALUES ('preserve@example.com','hash','보존','MANAGER','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        }
        var flyway = Flyway.configure().dataSource(url, "root", mysql.getPassword()).locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        try (var c = DriverManager.getConnection(url, "root", mysql.getPassword()); var sql = c.createStatement()) {
            sql.executeUpdate("INSERT INTO products(id,name,created_at,updated_at) VALUES (1,'부채',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO product_variants(product_id,sku,created_at,updated_at) VALUES (1,'FAN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            assertThat(flyway.migrate().migrationsExecuted).isZero(); flyway.validate();
            try (var rows = sql.executeQuery("SELECT (SELECT COUNT(*) FROM users WHERE email='preserve@example.com'),(SELECT COUNT(*) FROM product_variants WHERE sku='FAN')")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1); assertThat(rows.getInt(2)).isEqualTo(1);
            }
        }
    }

    private Long employee(String name, UserRole role, Long store) {
        var user = User.createPending(name + "@example.com", "hash", name, NOW);
        user.approve(role, store, manager, NOW); return users.saveAndFlush(user).getId();
    }
    private ProductCreatedResponse create(String sku) { return service.create(manager, new CreateProductRequest("부채", null, null, new CreateVariantRequest(sku, null, null))); }
    private String body(String sku) { return "{\"name\":\"부채\",\"firstVariant\":{\"sku\":\"" + sku + "\"}}"; }
    private String variantPath(ProductCreatedResponse created) { return "/api/v1/product-variants/" + created.firstVariant().id(); }
    private ResultActions call(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + sessions.open(actor).response().accessToken()));
    }
    private String attempt(Runnable task) { try { task.run(); return "OK"; } catch (BusinessException error) { return error.getErrorCode().name(); } }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("잠금 해제 대기 초과"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig { @Bean @Primary Clock catalogClock() { return Clock.fixed(NOW, ZoneOffset.UTC); } }
}
