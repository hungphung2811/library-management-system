package lms.circulation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lms.circulation.borrowing.domain.BorrowRule;
import lms.circulation.borrowing.domain.BorrowRuleRepository;
import lms.circulation.borrowing.domain.ReaderType;
import lms.circulation.reservation.application.ActiveTenantsPort;
import lms.circulation.tenant.infrastructure.messaging.TenantEventConsumer;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cần Docker. Chạy: mvn test -Dtest=CirculationTenantIsolationIT. Toàn bộ circulation-service với PostgreSQL thật
 * (ứng dụng chạy bằng circulation_app, chịu RLS). catalog-service và identity-service được giả bằng HTTP server của JDK:
 * trả dữ liệu theo token nhận được (token A chỉ thấy dữ liệu tenant A) và ghi lại header Authorization để kiểm
 * circulation chuyển tiếp đúng token (Review Focus 6). Không có Kafka: listener tắt, event vòng đời tenant được đưa
 * thẳng vào {@link TenantEventConsumer} đúng hình dạng hợp đồng Task 6.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=it",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.producer.properties.max.block.ms=200",
        "app.reservation.expire-cron=-"})
@AutoConfigureMockMvc
@Testcontainers
class CirculationTenantIsolationIT {

    static final UUID TENANT_A = UUID.randomUUID();
    static final UUID TENANT_B = UUID.randomUUID();
    static final String TOKEN_A = "token-tenant-a";
    static final String TOKEN_B = "token-tenant-b";
    static final UUID READER = UUID.randomUUID();

    /** Đầu sách theo token (tức theo tenant): title -> số bản sao còn. */
    static final Map<String, Map<UUID, Long>> CATALOG = new ConcurrentHashMap<>();
    static final List<String> CATALOG_AUTH = new CopyOnWriteArrayList<>();
    static final HttpServer FAKE = startFakeServices();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
        String base = "http://localhost:" + FAKE.getAddress().getPort();
        registry.add("app.clients.catalog.base-url", () -> base);
        registry.add("app.clients.identity.base-url", () -> base);
    }

    @AfterAll
    static void stop() {
        FAKE.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantEventConsumer tenantEvents;
    @Autowired BorrowRuleRepository borrowRules;
    @Autowired ObjectProvider<ActiveTenantsPort> activeTenants;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @BeforeEach
    void haiTenantDaDuocTao() {
        // idempotent: TenantCreated giao lại không tạo trùng quy tắc
        tenantEvents.onEvent(tenantCreated(TENANT_A));
        tenantEvents.onEvent(tenantCreated(TENANT_B));
    }

    // ---------- Mượn ----------

    @Test
    void phieuMuonCuaTenantBKhongThayTuTokenA() throws Exception {
        UUID copy = UUID.randomUUID();
        UUID idB = borrow(as(TOKEN_B, TENANT_B), copy);

        mvc.perform(get("/internal/borrowings/" + idB).with(as(TOKEN_A, TENANT_A))).andExpect(status().isNotFound());
        mvc.perform(get("/internal/borrowings/" + idB).with(as(TOKEN_B, TENANT_B))).andExpect(status().isOk());
        mvc.perform(post("/internal/borrowings/" + idB + "/returns").with(as(TOKEN_A, TENANT_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bookCopyId\":\"" + copy + "\"}"))
                .andExpect(status().isNotFound());
        // cùng bản sao đang mượn ở B vẫn mượn được ở A (unique một phần theo tenant)
        borrow(as(TOKEN_A, TENANT_A), copy);
    }

    @Test
    void tokenKhongCoTenantBi403TenantRequired() throws Exception {
        mvc.perform(post("/internal/borrowings").with(jwt().jwt(j -> j.subject(READER.toString()).claim("admin", true)))
                        .contentType(MediaType.APPLICATION_JSON).content(borrowBody(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    // ---------- Đặt trước + gọi catalog bằng token ----------

    @Test
    void datTruocHangDoiVaUqReservationsActiveTinhRiengTungTenant() throws Exception {
        UUID title = UUID.randomUUID();
        CATALOG.get(TOKEN_A).put(title, 0L);
        CATALOG.get(TOKEN_B).put(title, 0L);

        JsonNode a = reserve(as(TOKEN_A, TENANT_A), title);
        // cùng độc giả + cùng đầu sách ở tenant B: không trùng, đứng đầu hàng đợi riêng của B
        JsonNode b = reserve(as(TOKEN_B, TENANT_B), title);
        assertThat(a.path("queuePosition").asLong()).isEqualTo(1);
        assertThat(b.path("queuePosition").asLong()).isEqualTo(1);
        mvc.perform(post("/internal/reservations").with(as(TOKEN_A, TENANT_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"titleId\":\"" + title + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_RESERVED"));

        String mineA = mvc.perform(get("/internal/reservations").with(as(TOKEN_A, TENANT_A)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mineA).contains(a.path("id").asText()).doesNotContain(b.path("id").asText());
        mvc.perform(delete("/internal/reservations/" + b.path("id").asText()).with(as(TOKEN_A, TENANT_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void goiCatalogBangTokenCuaNguoiDungNenChiThayDauSachCuaTenantDo() throws Exception {
        UUID titleOnlyInB = UUID.randomUUID();
        CATALOG.get(TOKEN_B).put(titleOnlyInB, 0L);
        CATALOG_AUTH.clear();

        mvc.perform(post("/internal/reservations").with(as(TOKEN_A, TENANT_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"titleId\":\"" + titleOnlyInB + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TITLE_NOT_FOUND"));
        reserve(as(TOKEN_B, TENANT_B), titleOnlyInB);

        assertThat(CATALOG_AUTH).containsExactly("Bearer " + TOKEN_A, "Bearer " + TOKEN_B);
    }

    // ---------- Phạt (chưa có use case: kiểm ở tầng DB dưới RLS) ----------

    @Test
    void phiPhatCuaTenantBKhongThayTuTenantAVaKhongCoTenantThiRong() {
        UUID reader = UUID.randomUUID();
        AppRoleDatabase.asSuperuser(postgres, "INSERT INTO fines (id, tenant_id, reader_id, borrowing_id, book_copy_id, "
                + "reason, amount, status, created_at) VALUES (gen_random_uuid(), '" + TENANT_B + "', '" + reader
                + "', gen_random_uuid(), gen_random_uuid(), 'LATE_RETURN', 5000, 'UNPAID', now())");
        String sql = "SELECT count(*) FROM fines WHERE reader_id = ?";

        assertThat(TenantContext.callAs(TENANT_A, () -> jdbc.queryForObject(sql, Long.class, reader))).isZero();
        assertThat(jdbc.queryForObject(sql, Long.class, reader)).isZero();
        assertThat(TenantContext.callAs(TENANT_B, () -> jdbc.queryForObject(sql, Long.class, reader))).isEqualTo(1);
    }

    // ---------- Quy tắc mượn + tenant mới ----------

    @Test
    void quyTacMuonDocTheoTenant() {
        AppRoleDatabase.asSuperuser(postgres, "UPDATE borrow_rules SET max_items = 7 WHERE tenant_id = '" + TENANT_B
                + "' AND reader_type = 'STUDENT'");

        assertThat(TenantContext.callAs(TENANT_A, borrowRules::findAll))
                .contains(new BorrowRule(ReaderType.STUDENT, 5, 14));
        assertThat(TenantContext.callAs(TENANT_B, borrowRules::findAll))
                .contains(new BorrowRule(ReaderType.STUDENT, 7, 14));
        assertThat(borrowRules.findAll()).isEmpty();
        // SQL native không lọc tenant vẫn chỉ thấy tenant hiện tại (RLS), không có tenant thì 0 dòng
        assertThat(TenantContext.callAs(TENANT_A, () -> jdbc.queryForObject("SELECT count(*) FROM borrow_rules",
                Long.class))).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM borrow_rules", Long.class)).isZero();
    }

    @Test
    void taoTenantMoiCoQuyTacMuonMacDinhVaVaoDanhSachTenantActive() {
        UUID tenantC = UUID.randomUUID();
        assertThat(TenantContext.callAs(tenantC, borrowRules::findAll)).isEmpty();

        tenantEvents.onEvent(tenantCreated(tenantC));

        assertThat(TenantContext.callAs(tenantC, borrowRules::findAll)).containsExactlyInAnyOrder(
                new BorrowRule(ReaderType.STUDENT, 5, 14), new BorrowRule(ReaderType.LECTURER, 10, 30));
        ActiveTenantsPort port = activeTenants.getIfAvailable();
        assertThat(port).isNotNull();
        assertThat(port.activeTenantIds()).contains(tenantC, TENANT_A, TENANT_B);

        tenantEvents.onEvent("{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"TenantStatusChanged\","
                + "\"tenantId\":\"" + tenantC + "\",\"payload\":{\"tenantId\":\"" + tenantC + "\",\"status\":\"SUSPENDED\"}}");
        assertThat(port.activeTenantIds()).doesNotContain(tenantC);
    }

    @Test
    void eventTaoTenantThieuTenantIdKhongGhiGi() {
        UUID tenantD = UUID.randomUUID();
        tenantEvents.onEvent("{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"TenantCreated\","
                + "\"payload\":{\"tenantId\":\"" + tenantD + "\"}}");

        assertThat(activeTenants.getObject().activeTenantIds()).doesNotContain(tenantD);
        assertThat(TenantContext.callAs(tenantD, borrowRules::findAll)).isEmpty();
    }

    // ---------- Hỗ trợ ----------

    private static JwtRequestPostProcessor as(String token, UUID tenant) {
        return jwt().jwt(j -> j.tokenValue(token).subject(READER.toString()).claim("tenantId", tenant.toString())
                .claim("admin", true));
    }

    private static String tenantCreated(UUID tenant) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"TenantCreated\",\"tenantId\":\"" + tenant
                + "\",\"payload\":{\"tenantId\":\"" + tenant + "\"}}";
    }

    private static String borrowBody(UUID copy) {
        return "{\"userId\":\"" + READER + "\",\"bookCopyIds\":[\"" + copy + "\"]}";
    }

    private UUID borrow(JwtRequestPostProcessor token, UUID copy) throws Exception {
        String body = mvc.perform(post("/internal/borrowings").with(token)
                        .contentType(MediaType.APPLICATION_JSON).content(borrowBody(copy)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    private JsonNode reserve(JwtRequestPostProcessor token, UUID title) throws Exception {
        String body = mvc.perform(post("/internal/reservations").with(token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"titleId\":\"" + title + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** catalog (/internal/books/{id}) và identity (/internal/users/me/reader-profile) giả, phân theo token. */
    private static HttpServer startFakeServices() {
        CATALOG.put(TOKEN_A, new ConcurrentHashMap<>());
        CATALOG.put(TOKEN_B, new ConcurrentHashMap<>());
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/internal/books/", exchange -> {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                CATALOG_AUTH.add(String.valueOf(auth));
                String token = auth == null ? "" : auth.replaceFirst("^Bearer ", "");
                UUID id = UUID.fromString(exchange.getRequestURI().getPath().substring("/internal/books/".length()));
                Long available = CATALOG.getOrDefault(token, Map.of()).get(id);
                if (available == null) {
                    respond(exchange, 404, "{}");
                } else {
                    respond(exchange, 200, "{\"id\":\"" + id + "\",\"name\":\"Sách\",\"availableCopies\":" + available + "}");
                }
            });
            server.createContext("/internal/users/me/reader-profile", exchange -> {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                boolean known = auth != null && Set.of("Bearer " + TOKEN_A, "Bearer " + TOKEN_B).contains(auth);
                respond(exchange, known ? 200 : 401,
                        known ? "{\"userId\":\"" + READER + "\",\"cardValid\":true}" : "{}");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
