package lms.circulation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.circulation.borrowing.application.SaveBorrowingPort;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineLine;
import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineRepository;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0008, circulation: platform admin (token platformAdmin=true, không tenantId) đọc xuyên tenant
 * GET /internal/borrowings?readerId= và GET /internal/fines?readerId=. Cần Docker. Chạy:
 * {@code mvn test -Dtest=PlatformCrossTenantReadIT -DargLine=-Dapi.version=1.44}.
 * PostgreSQL thật, ứng dụng chạy bằng circulation_app (NOBYPASSRLS). Pool Hikari 1 connection để mọi request dùng lại
 * đúng một connection: chứng minh biến phiên app.tenant_scope không rò sang request kế tiếp. Dữ liệu hai tenant được
 * dựng qua repository trong TenantContext.callAs của từng tenant.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=it",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=10000",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.producer.properties.max.block.ms=200",
        "app.clients.catalog.base-url=http://localhost:1",
        "app.clients.identity.base-url=http://localhost:1",
        "app.reservation.expire-cron=-"})
@AutoConfigureMockMvc
@Testcontainers
class PlatformCrossTenantReadIT {

    static final UUID TENANT_A = UUID.randomUUID();
    static final UUID TENANT_B = UUID.randomUUID();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired SaveBorrowingPort borrowings;
    @Autowired FineRepository fines;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    /** Dữ liệu một độc giả ở hai tenant: A có 2 bản sao + phạt 5000, B có 3 bản sao + phạt 7000. */
    private record Seed(UUID reader, UUID borrowingA, UUID borrowingB) {
    }

    private Seed seed() {
        UUID reader = UUID.randomUUID();
        UUID a = seedTenant(TENANT_A, reader, 2, "5000");
        UUID b = seedTenant(TENANT_B, reader, 3, "7000");
        return new Seed(reader, a, b);
    }

    private UUID seedTenant(UUID tenant, UUID reader, int copies, String fineAmount) {
        return TenantContext.callAs(tenant, () -> {
            List<UUID> copyIds = new ArrayList<>();
            for (int i = 0; i < copies; i++) copyIds.add(UUID.randomUUID());
            BorrowingRecord record = BorrowingRecord.borrow(reader, copyIds, LocalDate.now(), 14);
            borrowings.save(record);
            fines.save(Fine.issue(reader, record.id().value(), copyIds.get(0),
                    new FineLine(FineReason.LATE_RETURN, new BigDecimal(fineAmount)), Instant.now()));
            return record.id().value();
        });
    }

    // ---------- (a) không tenantId: cả hai tenant ----------

    @Test
    void platformAdminKhongTenantIdThayBorrowingCuaCaHaiTenantVaDuItems() throws Exception {
        Seed s = seed();

        JsonNode list = getJson("/internal/borrowings?readerId=" + s.reader(), platformAdmin());

        assertThat(list.size()).isEqualTo(2);
        JsonNode a = byId(list, s.borrowingA());
        JsonNode b = byId(list, s.borrowingB());
        assertThat(a.path("tenantId").asText()).isEqualTo(TENANT_A.toString());
        assertThat(b.path("tenantId").asText()).isEqualTo(TENANT_B.toString());
        // borrowing_items không có field tenant ở JPA: items của mọi tenant phải đọc đủ
        assertThat(a.path("items").size()).isEqualTo(2);
        assertThat(b.path("items").size()).isEqualTo(3);
    }

    @Test
    void platformAdminKhongTenantIdThayFineCuaCaHaiTenantVaTongChuaThanhToan() throws Exception {
        Seed s = seed();

        JsonNode res = getJson("/internal/fines?readerId=" + s.reader(), platformAdmin());

        assertThat(res.path("fines").size()).isEqualTo(2);
        assertThat(res.path("totalUnpaid").decimalValue()).isEqualByComparingTo("12000");
        List<String> tenants = new ArrayList<>();
        res.path("fines").forEach(f -> tenants.add(f.path("tenantId").asText()));
        assertThat(tenants).containsExactlyInAnyOrder(TENANT_A.toString(), TENANT_B.toString());
    }

    // ---------- (b) tenantId = A ----------

    @Test
    void platformAdminChonTenantAChiThayA() throws Exception {
        Seed s = seed();

        JsonNode list = getJson("/internal/borrowings?readerId=" + s.reader() + "&tenantId=" + TENANT_A, platformAdmin());
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("id").asText()).isEqualTo(s.borrowingA().toString());
        assertThat(list.get(0).path("tenantId").asText()).isEqualTo(TENANT_A.toString());
        assertThat(list.get(0).path("items").size()).isEqualTo(2);

        JsonNode res = getJson("/internal/fines?readerId=" + s.reader() + "&tenantId=" + TENANT_A, platformAdmin());
        assertThat(res.path("fines").size()).isEqualTo(1);
        assertThat(res.path("fines").get(0).path("tenantId").asText()).isEqualTo(TENANT_A.toString());
        assertThat(res.path("totalUnpaid").decimalValue()).isEqualByComparingTo("5000");
    }

    // ---------- (c) tenantId không tồn tại, (d) sai định dạng ----------

    @Test
    void tenantIdKhongTonTaiTra200Rong() throws Exception {
        Seed s = seed();
        String unknown = UUID.randomUUID().toString();

        assertThat(getJson("/internal/borrowings?readerId=" + s.reader() + "&tenantId=" + unknown, platformAdmin()).size())
                .isZero();
        JsonNode res = getJson("/internal/fines?readerId=" + s.reader() + "&tenantId=" + unknown, platformAdmin());
        assertThat(res.path("fines").size()).isZero();
        assertThat(res.path("totalUnpaid").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void tenantIdSaiDinhDangTra400() throws Exception {
        UUID reader = UUID.randomUUID();
        mvc.perform(get("/internal/borrowings").param("readerId", reader.toString()).param("tenantId", "khong-phai-uuid")
                        .with(platformAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_ID"));
        mvc.perform(get("/internal/fines").param("readerId", reader.toString()).param("tenantId", "123")
                        .with(platformAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_ID"));
    }

    // ---------- Người gọi khác: tenantId bị bỏ qua ----------

    @Test
    void nguoiDungThuongGuiTenantIdKhacVanChiThayTenantCuaToken() throws Exception {
        Seed s = seed();
        for (JwtRequestPostProcessor token : List.of(tenantUser(TENANT_A, false), tenantUser(TENANT_A, true))) {
            JsonNode list = getJson("/internal/borrowings?readerId=" + s.reader() + "&tenantId=" + TENANT_B, token);
            assertThat(list.size()).isEqualTo(1);
            assertThat(list.get(0).path("id").asText()).isEqualTo(s.borrowingA().toString());

            JsonNode res = getJson("/internal/fines?readerId=" + s.reader() + "&tenantId=" + TENANT_B, token);
            assertThat(res.path("fines").size()).isEqualTo(1);
            assertThat(res.path("totalUnpaid").decimalValue()).isEqualByComparingTo("5000");
            // tenantId sai định dạng cũng bị bỏ qua, không lỗi
            assertThat(getJson("/internal/borrowings?readerId=" + s.reader() + "&tenantId=abc", token).size()).isEqualTo(1);
        }
    }

    @Test
    void platformAdminDaChonTenantTrongTokenBoQuaThamSoTenantId() throws Exception {
        Seed s = seed();
        JwtRequestPostProcessor token = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                .claim("platformAdmin", true).claim("tenantId", TENANT_A.toString()).claim("admin", true));

        JsonNode list = getJson("/internal/borrowings?readerId=" + s.reader() + "&tenantId=" + TENANT_B, token);
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("tenantId").asText()).isEqualTo(TENANT_A.toString());
        JsonNode res = getJson("/internal/fines?readerId=" + s.reader(), token);
        assertThat(res.path("fines").size()).isEqualTo(1);
    }

    // ---------- Không rò biến phiên (pool 1 connection) ----------

    @Test
    void khongRoBienPhienSangRequestSauTrenCungConnection() throws Exception {
        Seed s = seed();
        String borrowingsUrl = "/internal/borrowings?readerId=" + s.reader();
        String finesUrl = "/internal/fines?readerId=" + s.reader();

        for (int i = 0; i < 3; i++) {
            assertThat(getJson(borrowingsUrl, platformAdmin()).size()).isEqualTo(2);
            // ngay sau ALL, cùng connection duy nhất của pool: user tenant A chỉ thấy A
            JsonNode afterAll = getJson(borrowingsUrl, tenantUser(TENANT_A, false));
            assertThat(afterAll.size()).isEqualTo(1);
            assertThat(afterAll.get(0).path("tenantId").asText()).isEqualTo(TENANT_A.toString());
            assertThat(getJson(finesUrl, platformAdmin()).path("fines").size()).isEqualTo(2);
            assertThat(getJson(finesUrl, tenantUser(TENANT_A, true)).path("fines").size()).isEqualTo(1);
        }
        // truy vấn SQL thô không đặt tenant trên connection đó cũng không thấy gì (biến đã được đặt lại)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM borrowings WHERE user_id = ?", Long.class, s.reader()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM borrowing_items", Long.class)).isZero();
    }

    // ---------- Ghi bị từ chối ở phạm vi platform admin không tenant ----------

    @Test
    void platformAdminKhongTenantKhongGhiDuoc() throws Exception {
        UUID reader = UUID.randomUUID();
        long borrowingsBefore = count("borrowings");
        long finesBefore = count("fines");
        long itemsBefore = count("borrowing_items");
        String borrowBody = "{\"userId\":\"" + reader + "\",\"bookCopyIds\":[\"" + UUID.randomUUID() + "\"]}";

        mvc.perform(post("/internal/borrowings").with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(borrowBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        mvc.perform(post("/internal/fines/payments").with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"readerId\":\"" + reader + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));

        assertThat(count("borrowings")).isEqualTo(borrowingsBefore);
        assertThat(count("fines")).isEqualTo(finesBefore);
        assertThat(count("borrowing_items")).isEqualTo(itemsBefore);
    }

    @Test
    void phiaGhiTrongPhamViAllBiChanBoiTenantContextVaRls() {
        UUID reader = UUID.randomUUID();
        long before = count("borrowings");
        // Phạm vi ALL (đặt như interceptor) không cho dựng entity ghi: require() ném lỗi
        TenantContext.bindAllTenants();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> borrowings.save(
                            BorrowingRecord.borrow(reader, List.of(UUID.randomUUID()), LocalDate.now(), 14)))
                    .isInstanceOf(lms.tenant.TenantRequiredException.class);
        } finally {
            TenantContext.clear();
        }
        assertThat(count("borrowings")).isEqualTo(before);
    }

    // ---------- Hỗ trợ ----------

    private long count(String table) {
        try (var c = AppRoleDatabase.superuser(postgres); var st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode getJson(String url, JwtRequestPostProcessor token) throws Exception {
        String body = mvc.perform(get(url).with(token)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return json.readTree(body);
    }

    private static JsonNode byId(JsonNode list, UUID id) {
        for (JsonNode n : list) {
            if (id.toString().equals(n.path("id").asText())) return n;
        }
        throw new AssertionError("Không thấy " + id + " trong " + list);
    }

    private static JwtRequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("platformAdmin", true));
    }

    /** Người dùng của một tenant có khóa xem theo độc giả (hoặc admin tenant). */
    private static JwtRequestPostProcessor tenantUser(UUID tenant, boolean admin) {
        List<String> keys = List.of("BorrowingController/byReader", "FineController/byReader");
        return jwt().jwt(j -> {
            j.subject(UUID.randomUUID().toString()).claim("tenantId", tenant.toString());
            if (admin) j.claim("admin", true);
            else j.claim("permissions", keys);
        }).authorities(new SimpleGrantedAuthority(keys.get(0)), new SimpleGrantedAuthority(keys.get(1)));
    }
}
