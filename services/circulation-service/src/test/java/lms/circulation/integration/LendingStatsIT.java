package lms.circulation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.circulation.borrowing.application.SaveBorrowingPort;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.ItemCondition;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Thống kê và báo cáo mượn trả trên PostgreSQL thật, chạy bằng vai trò circulation_app (NOBYPASSRLS) nên native query
 * chịu RLS cộng điều kiện tenant tường minh. Cần Docker. Chạy:
 * {@code mvn -pl services/circulation-service -am test -Dtest=LendingStatsIT -DargLine=-Dapi.version=1.44}.
 * Mỗi test dùng tenant riêng (UUID ngẫu nhiên). identity/catalog trỏ vào cổng không có ai nghe để kiểm việc tra tên lỗi
 * vẫn xuất đủ dòng.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=it",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.producer.properties.max.block.ms=200",
        "app.clients.catalog.base-url=http://localhost:1",
        "app.clients.identity.base-url=http://localhost:1",
        "app.reservation.expire-cron=-"})
@AutoConfigureMockMvc
@Testcontainers
class LendingStatsIT {

    static final UUID TITLE_X = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID TITLE_Y = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID TITLE_Z = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    static final LocalDate REAL_TODAY = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SaveBorrowingPort borrowings;
    @Autowired EntityManager em;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static JwtRequestPostProcessor admin(UUID tenant) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", tenant.toString()).claim("admin", true));
    }

    /** Lưu phiếu của tenant; titles theo thứ tự bản sao (null = dòng cũ chưa có title_id); returned = số bản sao trả (GOOD) ở returnDate. */
    private List<UUID> seed(UUID tenant, LocalDate borrowDate, int loanDays, List<UUID> titles, int returned,
                            LocalDate returnDate, int lost) {
        return TenantContext.callAs(tenant, () -> {
            List<UUID> copyIds = new ArrayList<>();
            for (int i = 0; i < titles.size(); i++) copyIds.add(UUID.randomUUID());
            BorrowingRecord record = BorrowingRecord.borrow(UUID.randomUUID(), copyIds, borrowDate, loanDays);
            Map<UUID, UUID> titleByCopy = new java.util.HashMap<>();
            for (int i = 0; i < titles.size(); i++) if (titles.get(i) != null) titleByCopy.put(copyIds.get(i), titles.get(i));
            record.attachTitles(titleByCopy);
            for (int i = 0; i < returned; i++) record.returnCopy(copyIds.get(i), ItemCondition.GOOD, returnDate);
            for (int i = returned; i < returned + lost; i++) record.reportLost(copyIds.get(i), returnDate);
            borrowings.save(record);
            return copyIds;
        });
    }

    private void fine(UUID tenant, String amount, String status) throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres);
             PreparedStatement ps = c.prepareStatement("INSERT INTO fines (id, tenant_id, reader_id, borrowing_id, book_copy_id, "
                     + "reason, amount, status, created_at, paid_at) VALUES (?, ?, ?, ?, ?, 'LATE_RETURN', ?::numeric, ?, now(), "
                     + "CASE WHEN ? = 'PAID' THEN now() ELSE NULL END)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenant);
            ps.setObject(3, UUID.randomUUID());
            ps.setObject(4, UUID.randomUUID());
            ps.setObject(5, UUID.randomUUID());
            ps.setString(6, amount);
            ps.setString(7, status);
            ps.setString(8, status);
            ps.executeUpdate();
        }
    }

    private void seedScenario(UUID tenant) throws Exception {
        // P1: quá hạn (due 03-06), 2 bản sao cùng đầu sách X
        seed(tenant, LocalDate.of(2026, 2, 20), 14, List.of(TITLE_X, TITLE_X), 0, null, 0);
        // P2: due đúng hôm nay (03-10): CHƯA quá hạn
        seed(tenant, LocalDate.of(2026, 2, 24), 14, List.of(TITLE_Y), 0, null, 0);
        // P3: mượn hôm nay
        seed(tenant, TODAY, 14, List.of(TITLE_Y), 0, null, 0);
        // P4: quá hạn một phần: 1 bản sao đã trả hôm nay, 1 bản sao (dòng cũ không title_id) còn mượn
        seed(tenant, LocalDate.of(2026, 2, 1), 14, java.util.Arrays.asList(TITLE_Z, null), 1, TODAY, 0);
        // P5: đã trả hết trước đó
        seed(tenant, LocalDate.of(2026, 2, 1), 14, List.of(TITLE_Z), 1, LocalDate.of(2026, 2, 10), 0);
        // P6: báo mất hôm nay: không tính là trả, không còn ACTIVE
        seed(tenant, LocalDate.of(2026, 2, 28), 14, List.of(TITLE_Y), 0, TODAY, 1);
        fine(tenant, "10000", "UNPAID");
        fine(tenant, "25000.50", "UNPAID");
        fine(tenant, "99999", "PAID");
    }

    private JsonNode getJson(String url, UUID tenant) throws Exception {
        return json.readTree(mvc.perform(get(url).with(admin(tenant))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void summaryDemDungTheoDinhNghiaVaPhatChuaThu() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seedScenario(a);
        seed(b, LocalDate.of(2026, 1, 1), 14, List.of(TITLE_X, TITLE_X, TITLE_X), 0, null, 0); // tenant khác: không lẫn
        fine(b, "777", "UNPAID");

        JsonNode r = getJson("/internal/reporting/lending/summary?asOf=2026-03-10", a);

        assertThat(r.path("asOf").asText()).isEqualTo("2026-03-10");
        JsonNode l = r.path("lending");
        assertThat(l.path("activeBorrowings").asLong()).isEqualTo(4);     // P1, P2, P3, P4
        assertThat(l.path("overdueBorrowings").asLong()).isEqualTo(2);    // P1, P4 (P2 đến hạn hôm nay chưa quá hạn)
        assertThat(l.path("overdueCopies").asLong()).isEqualTo(3);        // P1 hai bản sao + P4 một bản sao còn lại
        assertThat(l.path("borrowedToday").asLong()).isEqualTo(1);        // P3
        assertThat(l.path("returnedToday").asLong()).isEqualTo(1);        // P4 bản trả GOOD; P6 báo mất không tính
        assertThat(r.path("fines").path("unpaidCount").asLong()).isEqualTo(2);
        assertThat(r.path("fines").path("unpaidAmount").decimalValue()).isEqualByComparingTo("35000.50");

        // Tenant B chỉ thấy dữ liệu của mình
        JsonNode rb = getJson("/internal/reporting/lending/summary?asOf=2026-03-10", b);
        assertThat(rb.path("lending").path("activeBorrowings").asLong()).isEqualTo(1);
        assertThat(rb.path("lending").path("overdueCopies").asLong()).isEqualTo(3);
        assertThat(rb.path("fines").path("unpaidCount").asLong()).isEqualTo(1);
    }

    @Test
    void tenantKhongCoDuLieuThiToanSoKhong() throws Exception {
        JsonNode r = getJson("/internal/reporting/lending/summary?asOf=2026-03-10", UUID.randomUUID());

        assertThat(r.path("lending").path("activeBorrowings").asLong()).isZero();
        assertThat(r.path("fines").path("unpaidCount").asLong()).isZero();
        assertThat(r.path("fines").path("unpaidAmount").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void bieuDoMuonTraTheoNgayVaTuanIso() throws Exception {
        UUID a = UUID.randomUUID();
        seedScenario(a);

        JsonNode day = getJson("/internal/reporting/lending/borrowing-trend?from=2026-03-09&to=2026-03-10&granularity=day", a);
        assertThat(day.path("points")).hasSize(2);
        assertThat(day.path("points").get(0).path("borrowed").asLong()).isZero();
        assertThat(day.path("points").get(1).path("bucket").asText()).isEqualTo("2026-03-10");
        assertThat(day.path("points").get(1).path("borrowed").asLong()).isEqualTo(1);
        assertThat(day.path("points").get(1).path("returned").asLong()).isEqualTo(1);

        JsonNode week = getJson("/internal/reporting/lending/borrowing-trend?from=2026-03-02&to=2026-03-10&granularity=week", a);
        assertThat(week.path("points")).hasSize(2);
        assertThat(week.path("points").get(0).path("bucket").asText()).isEqualTo("2026-03-02");
        assertThat(week.path("points").get(1).path("bucket").asText()).isEqualTo("2026-03-09");
        assertThat(week.path("points").get(1).path("borrowed").asLong()).isEqualTo(1);

        // Tổng borrowed của khoảng bằng số bản sao có borrowDate trong khoảng (AC9)
        JsonNode all = getJson("/internal/reporting/lending/borrowing-trend?from=2026-02-01&to=2026-03-10", a);
        long borrowed = 0;
        for (JsonNode p : all.path("points")) borrowed += p.path("borrowed").asLong();
        assertThat(all.path("points")).hasSize(38);
        assertThat(borrowed).isEqualTo(2 + 1 + 1 + 2 + 1 + 1); // P1(2) P2 P3 P4(2) P5 P6
    }

    @Test
    void topSachMuonBoDongCuKhongCoTitleIdSapTheoCountRoiTitleId() throws Exception {
        UUID a = UUID.randomUUID();
        seedScenario(a);

        JsonNode top = getJson("/internal/reporting/lending/top-titles?from=2026-02-01&to=2026-03-10&limit=10", a);

        // X: 2 (P1); Y: 3 (P2, P3, P6); Z: 2 (P4 bản có title + P5), dòng cũ title_id NULL bị bỏ qua
        assertThat(top.path("items")).hasSize(3);
        assertThat(top.path("items").get(0).path("titleId").asText()).isEqualTo(TITLE_Y.toString());
        assertThat(top.path("items").get(0).path("count").asLong()).isEqualTo(3);
        assertThat(top.path("items").get(1).path("titleId").asText()).isEqualTo(TITLE_X.toString()); // đồng hạng: titleId tăng
        assertThat(top.path("items").get(2).path("titleId").asText()).isEqualTo(TITLE_Z.toString());

        JsonNode limited = getJson("/internal/reporting/lending/top-titles?from=2026-02-01&to=2026-03-10&limit=1", a);
        assertThat(limited.path("items")).hasSize(1);
    }

    @Test
    void csvQuaHanChiCoTenantHienTaiTraTenLoiVanXuatDuDongVaSapQuaHanLauTruoc() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seed(a, REAL_TODAY.minusDays(30), 14, List.of(TITLE_X, TITLE_Y), 0, null, 0);   // due = hôm nay - 16, 2 bản sao
        seed(a, REAL_TODAY.minusDays(50), 14, List.of(TITLE_X), 0, null, 0);            // due = hôm nay - 36
        seed(a, REAL_TODAY.minusDays(14), 14, List.of(TITLE_X), 0, null, 0);            // due hôm nay: không quá hạn
        seed(b, REAL_TODAY.minusDays(60), 14, List.of(TITLE_X, TITLE_X), 0, null, 0);   // tenant khác

        var res = mvc.perform(get("/internal/reporting/lending/overdue-borrowings.csv").with(admin(a)))
                .andExpect(status().isOk()).andReturn().getResponse();
        byte[] body = res.getContentAsByteArray();
        String[] lines = new String(body, 3, body.length - 3, StandardCharsets.UTF_8).split("\r\n");

        assertThat(res.getHeader("Content-Disposition")).startsWith("attachment; filename=\"overdue-borrowings-");
        assertThat(res.getHeader("X-Report-Truncated")).isNull();
        assertThat(lines).hasSize(1 + 3);
        assertThat(lines[1]).endsWith(",36");
        assertThat(lines[2]).endsWith(",16");
        assertThat(lines[3]).endsWith(",16");
        // identity/catalog không gọi được: tên rỗng nhưng vẫn đủ dòng (readerName, titleName, barcode)
        assertThat(lines[1].split(",", -1)[2]).isEmpty();
        assertThat(lines[1].split(",", -1)[3]).isEmpty();
    }

    @Test
    void hintTimeoutCuaNativeQueryHuyTruyVanChamTrenHibernateVaPgJdbc() {
        assertThatThrownBy(() -> em.createNativeQuery("select pg_sleep(5)")
                .setHint("jakarta.persistence.query.timeout", 500).getResultList())
                .isInstanceOf(RuntimeException.class);
    }
}
