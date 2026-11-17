package lms.circulation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.circulation.borrowing.application.SaveBorrowingPort;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.ItemCondition;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
 * GET /internal/borrowings/search trên PostgreSQL thật, ứng dụng chạy bằng circulation_app (NOBYPASSRLS), pool Hikari
 * 1 connection. Cần Docker. Chạy: {@code mvn test -Dtest=BorrowingSearchIT -DargLine=-Dapi.version=1.44}.
 * Mỗi test dùng độc giả riêng (readerId ngẫu nhiên) để các test không nhiễm dữ liệu của nhau.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=it",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=10000",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.producer.properties.max.block.ms=200",
        "app.clients.catalog.base-url=http://localhost:1",
        "app.clients.identity.base-url=http://localhost:1",
        "app.reservation.expire-cron=-"})
@AutoConfigureMockMvc
@Testcontainers
class BorrowingSearchIT {

    static final UUID TENANT_A = UUID.randomUUID();
    static final UUID TENANT_B = UUID.randomUUID();
    static final String KEY = "BorrowingController/search";
    static final LocalDate TODAY = LocalDate.now();

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
    @Autowired EntityManagerFactory emf;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    /**
     * Lưu một phiếu ở tenant với ngày mượn tùy ý (hạn = borrowDate + loanDays). returned = số bản sao đã trả
     * (trả hết thì phiếu RETURNED, trả một phần thì vẫn ACTIVE).
     */
    private UUID seed(UUID tenant, UUID reader, LocalDate borrowDate, int loanDays, int copies, int returned) {
        return TenantContext.callAs(tenant, () -> {
            List<UUID> copyIds = new ArrayList<>();
            for (int i = 0; i < copies; i++) copyIds.add(UUID.randomUUID());
            BorrowingRecord record = BorrowingRecord.borrow(reader, copyIds, borrowDate, loanDays);
            for (int i = 0; i < returned; i++) {
                record.returnCopy(copyIds.get(i), ItemCondition.GOOD, borrowDate.plusDays(1));
            }
            borrowings.save(record);
            return record.id().value();
        });
    }

    // ---------- Lọc ----------

    @Test
    void locTheoStatusVaOverdueTheoNgayHienTai() throws Exception {
        UUID reader = UUID.randomUUID();
        UUID dangMuon = seed(TENANT_A, reader, TODAY.minusDays(3), 14, 1, 0);
        UUID hanHomNay = seed(TENANT_A, reader, TODAY.minusDays(14), 14, 1, 0); // due = hôm nay: chưa quá hạn
        UUID quaHan = seed(TENANT_A, reader, TODAY.minusDays(30), 14, 2, 0);
        UUID traMotPhan = seed(TENANT_A, reader, TODAY.minusDays(40), 14, 2, 1); // còn 1 bản sao, quá hạn
        UUID daTra = seed(TENANT_A, reader, TODAY.minusDays(50), 14, 2, 2); // đã trả hết, hạn đã qua

        String base = "/internal/borrowings/search?readerId=" + reader;
        assertThat(ids(search(base, tenantUser(TENANT_A)))).containsExactlyInAnyOrder(
                dangMuon, hanHomNay, quaHan, traMotPhan, daTra);
        assertThat(ids(search(base + "&status=ACTIVE", tenantUser(TENANT_A)))).containsExactlyInAnyOrder(
                dangMuon, hanHomNay, quaHan, traMotPhan);
        assertThat(ids(search(base + "&status=RETURNED", tenantUser(TENANT_A)))).containsExactly(daTra);
        assertThat(ids(search(base + "&overdue=true", tenantUser(TENANT_A)))).containsExactlyInAnyOrder(
                quaHan, traMotPhan);
        assertThat(ids(search(base + "&overdue=true&status=ACTIVE", tenantUser(TENANT_A)))).containsExactlyInAnyOrder(
                quaHan, traMotPhan);
        assertThat(search(base + "&overdue=true&status=RETURNED", tenantUser(TENANT_A)).path("total").asLong()).isZero();
        assertThat(ids(search(base + "&overdue=false", tenantUser(TENANT_A)))).hasSize(5);
        assertThat(search(base + "&status=ACTIVE", tenantUser(TENANT_A)).path("total").asLong()).isEqualTo(4);
    }

    @Test
    void locTheoReaderChiThayPhieuCuaDocGiaDo() throws Exception {
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        UUID p1 = seed(TENANT_A, r1, TODAY.minusDays(1), 14, 1, 0);
        seed(TENANT_A, r2, TODAY.minusDays(1), 14, 1, 0);

        JsonNode res = search("/internal/borrowings/search?readerId=" + r1, tenantUser(TENANT_A));

        assertThat(ids(res)).containsExactly(p1);
        assertThat(res.path("items").get(0).path("userId").asText()).isEqualTo(r1.toString());
        assertThat(res.path("items").get(0).path("tenantId").asText()).isEqualTo(TENANT_A.toString());
        assertThat(res.path("items").get(0).path("fines").size()).isZero();
    }

    // ---------- Phân trang, sắp xếp, items ----------

    @Test
    void phanTrangKhongTrungKhongThieuSapXepDungKhiPhieuNhieuItems() throws Exception {
        UUID reader = UUID.randomUUID();
        // 7 phiếu, số items 1..3, có 3 phiếu cùng borrowDate (sắp theo id tăng dần)
        List<UUID> all = new ArrayList<>();
        int[] daysAgo = {1, 2, 2, 2, 5, 6, 9};
        for (int i = 0; i < daysAgo.length; i++) {
            all.add(seed(TENANT_A, reader, TODAY.minusDays(daysAgo[i]), 14, 1 + (i % 3), 0));
        }
        int totalItems = 0;
        for (int i = 0; i < daysAgo.length; i++) totalItems += 1 + (i % 3);

        List<UUID> collected = new ArrayList<>();
        int itemsSeen = 0;
        for (int page = 0; page < 4; page++) {
            JsonNode res = search("/internal/borrowings/search?readerId=" + reader + "&page=" + page + "&size=3",
                    tenantUser(TENANT_A));
            assertThat(res.path("total").asLong()).isEqualTo(7); // đếm phiếu, không nhân theo items
            assertThat(res.path("page").asInt()).isEqualTo(page);
            assertThat(res.path("size").asInt()).isEqualTo(3);
            assertThat(res.path("items").size()).isEqualTo(page < 2 ? 3 : page == 2 ? 1 : 0);
            for (JsonNode n : res.path("items")) {
                collected.add(UUID.fromString(n.path("id").asText()));
                itemsSeen += n.path("items").size();
                assertThat(n.path("items").size()).isBetween(1, 3);
            }
        }
        assertThat(collected).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(all);
        assertThat(itemsSeen).isEqualTo(totalItems);

        // thứ tự: borrowDate giảm dần, cùng ngày thì id tăng dần
        JsonNode one = search("/internal/borrowings/search?readerId=" + reader + "&size=100", tenantUser(TENANT_A));
        List<String[]> keys = new ArrayList<>();
        for (JsonNode n : one.path("items")) keys.add(new String[]{n.path("borrowDate").asText(), n.path("id").asText()});
        List<String[]> expected = new ArrayList<>(keys);
        expected.sort(Comparator.<String[], String>comparing(k -> k[0]).reversed().thenComparing(k -> k[1]));
        assertThat(keys).usingRecursiveComparison().isEqualTo(expected);
        assertThat(keys).hasSize(7);
    }

    @Test
    void khongNPlus1KhiNapItemsCuaCaTrang() throws Exception {
        UUID reader = UUID.randomUUID();
        for (int i = 0; i < 8; i++) seed(TENANT_A, reader, TODAY.minusDays(i), 14, 3, 0);
        SessionFactory sf = emf.unwrap(SessionFactory.class);
        String url = "/internal/borrowings/search?readerId=" + reader + "&size=8";
        search(url, tenantUser(TENANT_A)); // làm nóng
        sf.getStatistics().clear();

        JsonNode res = search(url, tenantUser(TENANT_A));

        assertThat(res.path("items").size()).isEqualTo(8);
        res.path("items").forEach(n -> assertThat(n.path("items").size()).isEqualTo(3));
        // 1 truy vấn trang + (có thể 1 đếm) + nạp items theo lô: không phải 1 truy vấn cho mỗi phiếu
        assertThat(sf.getStatistics().getPrepareStatementCount()).isBetween(1L, 4L);
    }

    // ---------- Tenant ----------

    @Test
    void nguoiDungTenantAKhongThayTenantBKeCaKhiGuiTenantId() throws Exception {
        UUID reader = UUID.randomUUID();
        UUID a = seed(TENANT_A, reader, TODAY.minusDays(1), 14, 2, 0);
        UUID b = seed(TENANT_B, reader, TODAY.minusDays(1), 14, 3, 0);
        String base = "/internal/borrowings/search?readerId=" + reader;

        assertThat(ids(search(base, tenantUser(TENANT_A)))).containsExactly(a);
        assertThat(ids(search(base + "&tenantId=" + TENANT_B, tenantUser(TENANT_A)))).containsExactly(a);
        assertThat(ids(search(base + "&tenantId=khong-phai-uuid", tenantUser(TENANT_A)))).containsExactly(a);
        assertThat(ids(search(base, tenantUser(TENANT_B)))).containsExactly(b);
        assertThat(search(base + "&tenantId=" + TENANT_A, tenantUser(TENANT_B)).path("total").asLong()).isEqualTo(1);
        // admin tenant cũng bị khóa trong tenant của token
        JwtRequestPostProcessor adminA = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                .claim("tenantId", TENANT_A.toString()).claim("admin", true));
        assertThat(ids(search(base + "&tenantId=" + TENANT_B, adminA))).containsExactly(a);
    }

    @Test
    void platformAdminKhongTenantThayCaHaiTenantHoacLocTheoTenantId() throws Exception {
        UUID reader = UUID.randomUUID();
        UUID a = seed(TENANT_A, reader, TODAY.minusDays(1), 14, 2, 0);
        UUID b = seed(TENANT_B, reader, TODAY.minusDays(2), 14, 3, 0);
        String base = "/internal/borrowings/search?readerId=" + reader;

        JsonNode all = search(base, platformAdmin());
        assertThat(ids(all)).containsExactly(a, b);
        assertThat(all.path("total").asLong()).isEqualTo(2);
        assertThat(all.path("items").get(0).path("tenantId").asText()).isEqualTo(TENANT_A.toString());
        assertThat(all.path("items").get(1).path("tenantId").asText()).isEqualTo(TENANT_B.toString());
        assertThat(all.path("items").get(1).path("items").size()).isEqualTo(3);

        assertThat(ids(search(base + "&tenantId=" + TENANT_A, platformAdmin()))).containsExactly(a);
        assertThat(ids(search(base + "&tenantId=" + TENANT_B, platformAdmin()))).containsExactly(b);
        assertThat(search(base + "&tenantId=" + UUID.randomUUID(), platformAdmin()).path("total").asLong()).isZero();
        mvc.perform(get("/internal/borrowings/search").param("tenantId", "abc").with(platformAdmin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TENANT_ID"));
    }

    @Test
    void platformAdminPhanTrangXuyenTenantDungTotalVaKhongTrung() throws Exception {
        UUID reader = UUID.randomUUID();
        Set<UUID> expected = new HashSet<>();
        for (int i = 0; i < 3; i++) expected.add(seed(TENANT_A, reader, TODAY.minusDays(i), 14, 2, 0));
        for (int i = 0; i < 3; i++) expected.add(seed(TENANT_B, reader, TODAY.minusDays(i), 14, 3, 0));

        List<UUID> collected = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode res = search("/internal/borrowings/search?readerId=" + reader + "&page=" + page + "&size=2",
                    platformAdmin());
            assertThat(res.path("total").asLong()).isEqualTo(6);
            assertThat(res.path("items").size()).isEqualTo(2);
            collected.addAll(ids(res));
        }
        assertThat(collected).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
    }

    // ---------- Quyền, tham số sai, không rò biến phiên ----------

    @Test
    void thieuKhoaBi403VaThamSoSaiBi400() throws Exception {
        mvc.perform(get("/internal/borrowings/search").with(jwt().jwt(j -> j.claim("tenantId", TENANT_A.toString()))
                        .authorities(new SimpleGrantedAuthority("BorrowingController/byReader"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/borrowings/search").param("status", "XYZ").with(tenantUser(TENANT_A)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/internal/borrowings/search").param("size", "101").with(tenantUser(TENANT_A)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/internal/borrowings/search").param("page", "-1").with(tenantUser(TENANT_A)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void khongRoBienPhienSangRequestSauTrenCungConnection() throws Exception {
        UUID reader = UUID.randomUUID();
        UUID a = seed(TENANT_A, reader, TODAY.minusDays(1), 14, 1, 0);
        seed(TENANT_B, reader, TODAY.minusDays(1), 14, 1, 0);
        String url = "/internal/borrowings/search?readerId=" + reader;

        for (int i = 0; i < 3; i++) {
            assertThat(search(url, platformAdmin()).path("total").asLong()).isEqualTo(2);
            // ngay sau ALL, cùng connection duy nhất: user tenant A chỉ thấy A
            assertThat(ids(search(url, tenantUser(TENANT_A)))).containsExactly(a);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM borrowings WHERE user_id = ?", Long.class, reader)).isZero();
    }

    // ---------- Hỗ trợ ----------

    private JsonNode search(String url, JwtRequestPostProcessor token) throws Exception {
        String body = mvc.perform(get(url).with(token)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return json.readTree(body);
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("items").forEach(n -> ids.add(UUID.fromString(n.path("id").asText())));
        return ids;
    }

    private static JwtRequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("platformAdmin", true));
    }

    private static JwtRequestPostProcessor tenantUser(UUID tenant) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", tenant.toString())
                .claim("permissions", List.of(KEY))).authorities(new SimpleGrantedAuthority(KEY));
    }
}
