package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.stock.application.CatalogLookupPort;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cần Docker. Chạy: mvn -o -pl services/sales-service -am test -Dtest=SalesReportingIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44. Kiểm câu SQL native của thống kê/báo cáo
 * (ADR-0018) trên PostgreSQL thật bằng vai trò sales_app: gom theo ngày giờ VN, dòng tiền (đơn thanh toán rồi hủy),
 * khoản hoàn PENDING không tính, top bán trừ trả hàng, cô lập tenant, CSV không có dữ liệu liên hệ khách.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/sales",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.sales.events.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class SalesReportingIT {

    private static final String B = "/internal/sales/reporting";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    @MockitoBean
    CatalogLookupPort catalog;

    static final UUID A = UUID.randomUUID();
    static final UUID C = UUID.randomUUID();
    static final UUID T1 = UUID.randomUUID();
    static final UUID T2 = UUID.randomUUID();
    static final UUID T3 = UUID.randomUUID();
    static boolean seeded;

    @BeforeEach
    void seed() throws Exception {
        when(catalog.lookup(any())).thenReturn(Map.of());
        synchronized (SalesReportingIT.class) {
            if (seeded) {
                return;
            }
            seeded = true;
            try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
                // ----- tenant A -----
                UUID o1 = order(s, A, "A-1", "POS", "COMPLETED", "PAID", "2026-11-16T16:30:00Z", "2026-11-16T16:30:00Z",
                        100_000);
                UUID l1 = line(s, A, o1, T1, "Sach Mot", 3, 100_000);
                UUID o2 = order(s, A, "A-2", "ONLINE", "CANCELLED", "REFUNDED", "2026-11-16T16:40:00Z",
                        "2026-11-16T17:10:00Z", 50_000);
                line(s, A, o2, T2, "Sach Hai", 1, 50_000);
                order(s, A, "A-3", "ONLINE", "PENDING_CONFIRMATION", "UNPAID", "2026-11-16T05:00:00Z", null, 70_000);
                s.execute("INSERT INTO refunds (id, tenant_id, order_id, source, amount, status, method, created_at, "
                        + "refunded_at) VALUES ('" + UUID.randomUUID() + "', '" + A + "', '" + o2 + "', 'CANCELLATION', "
                        + "50000, 'REFUNDED', 'CASH', '2026-11-17T02:00:00Z', '2026-11-17T03:00:00Z')");
                UUID r1 = UUID.randomUUID();
                s.execute("INSERT INTO sales_returns (id, tenant_id, return_no, order_id, reason, refund_total, "
                        + "client_request_id, created_at) VALUES ('" + r1 + "', '" + A + "', 'R-1', '" + o1
                        + "', 'loi', 30000, '" + UUID.randomUUID() + "', '2026-11-17T02:00:00Z')");
                s.execute("INSERT INTO sales_return_lines (id, tenant_id, return_id, order_line_id, quantity, "
                        + "disposition, refund_amount) VALUES ('" + UUID.randomUUID() + "', '" + A + "', '" + r1
                        + "', '" + l1 + "', 1, 'RESTOCK', 30000)");
                // khoản hoàn của phiếu trả còn PENDING: không tính vào refunds
                s.execute("INSERT INTO refunds (id, tenant_id, order_id, source, return_id, amount, status, "
                        + "created_at) VALUES ('" + UUID.randomUUID() + "', '" + A + "', '" + o1 + "', 'RETURN', '"
                        + r1 + "', 30000, 'PENDING', '2026-11-17T02:00:00Z')");
                stock(s, A, T1, 2);
                stock(s, A, T3, 50);
                // ----- tenant C: số lớn, không được lọt sang A -----
                UUID oc = order(s, C, "C-1", "POS", "COMPLETED", "PAID", "2026-11-16T05:00:00Z", "2026-11-16T05:00:00Z",
                        999_999);
                line(s, C, oc, T1, "Sach Mot", 99, 999_999);
                stock(s, C, T2, 0);
            }
        }
    }

    private static UUID order(Statement s, UUID tenant, String no, String channel, String status, String payment,
                              String createdAt, String paidAt, long total) throws Exception {
        UUID id = UUID.randomUUID();
        s.execute("INSERT INTO sales_orders (id, tenant_id, order_no, channel, status, payment_status, "
                + "payment_method, walk_in_name, walk_in_phone, vat_rate_bps, prices_include_vat, subtotal, "
                + "vat_total, grand_total, client_request_id, created_at, paid_at) VALUES ('" + id + "', '" + tenant
                + "', '" + no + "', '" + channel + "', '" + status + "', '" + payment + "', 'CASH', 'Khach Bi Mat', "
                + "'0900000000', 500, true, " + total + ", 0, " + total + ", '" + UUID.randomUUID() + "', '"
                + createdAt + "', " + (paidAt == null ? "NULL" : "'" + paidAt + "'") + ")");
        return id;
    }

    private static UUID line(Statement s, UUID tenant, UUID order, UUID title, String name, int qty, long total)
            throws Exception {
        UUID id = UUID.randomUUID();
        s.execute("INSERT INTO sales_order_lines (id, tenant_id, order_id, line_no, title_id, title_name, quantity, "
                + "unit_price, amount, vat_amount, line_total) VALUES ('" + id + "', '" + tenant + "', '" + order
                + "', 1, '" + title + "', '" + name + "', " + qty + ", " + (total / qty) + ", " + total + ", 0, "
                + total + ")");
        return id;
    }

    private static void stock(Statement s, UUID tenant, UUID title, int onHand) throws Exception {
        s.execute("INSERT INTO stock_items (id, tenant_id, title_id, on_hand, reserved) VALUES ('"
                + UUID.randomUUID() + "', '" + tenant + "', '" + title + "', " + onHand + ", 0)");
    }

    private static RequestPostProcessor admin(UUID tenant) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", tenant.toString())
                .claim("admin", true).claim("modules", List.of("SALES")));
    }

    @Test
    void doanhThuTheoDongTienVaNgayGioVn() throws Exception {
        mvc.perform(get(B + "/revenue-trend").param("from", "2026-11-16").param("to", "2026-11-17").with(admin(A)))
                .andExpect(status().isOk())
                // 23:30 VN ngày 16 (16:30Z) vào ngày 16; 00:10 VN ngày 17 (17:10Z) vào ngày 17
                .andExpect(jsonPath("$.points[0].date").value("2026-11-16"))
                .andExpect(jsonPath("$.points[0].gross").value(100_000))
                .andExpect(jsonPath("$.points[0].orders").value(1))
                .andExpect(jsonPath("$.points[0].refunds").value(0))
                // đơn A-2 đã thanh toán rồi hủy: vẫn vào gross ngày thanh toán; hoàn 50000 trừ ở ngày hoàn (17/11 10:00 VN)
                .andExpect(jsonPath("$.points[1].gross").value(50_000))
                .andExpect(jsonPath("$.points[1].orders").value(1))
                .andExpect(jsonPath("$.points[1].refunds").value(50_000))
                .andExpect(jsonPath("$.points[1].net").value(0))
                // đơn UNPAID, khoản hoàn PENDING và tenant khác không ảnh hưởng
                .andExpect(jsonPath("$.totals.gross").value(150_000))
                .andExpect(jsonPath("$.totals.refunds").value(50_000))
                .andExpect(jsonPath("$.totals.net").value(100_000))
                .andExpect(jsonPath("$.totals.orders").value(2));
    }

    @Test
    void summaryTheoAsOfDemDonChoXacNhanVaTonThap() throws Exception {
        mvc.perform(get(B + "/summary").param("asOf", "2026-11-16").with(admin(A))).andExpect(status().isOk())
                .andExpect(jsonPath("$.ordersToday").value(1)).andExpect(jsonPath("$.revenueToday").value(100_000))
                .andExpect(jsonPath("$.pendingOnlineOrders").value(1))
                // T1 tồn 2 <= ngưỡng chung 5 là tồn thấp; T3 tồn 50 thì không; tồn của tenant khác không tính
                .andExpect(jsonPath("$.lowStockCount").value(1))
                .andExpect(jsonPath("$.lowStockItems[0].titleId").value(T1.toString()))
                .andExpect(jsonPath("$.lowStockItems[0].available").value(2))
                .andExpect(jsonPath("$.lowStockItems[0].threshold").value(5));
        mvc.perform(get(B + "/summary").param("asOf", "2026-11-17").with(admin(A))).andExpect(status().isOk())
                .andExpect(jsonPath("$.ordersToday").value(1)).andExpect(jsonPath("$.grossToday").value(50_000))
                .andExpect(jsonPath("$.refundsToday").value(50_000)).andExpect(jsonPath("$.revenueToday").value(0));
    }

    @Test
    void topBanTruPhanTraHangVaCoLapTenant() throws Exception {
        mvc.perform(get(B + "/top-titles").param("from", "2026-11-16").param("to", "2026-11-17").with(admin(A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].titleId").value(T1.toString()))
                .andExpect(jsonPath("$.items[0].titleName").value("Sach Mot"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))      // 3 bán - 1 trả
                .andExpect(jsonPath("$.items[0].revenue").value(70_000))  // 100000 - 30000
                .andExpect(jsonPath("$.items[1].titleId").value(T2.toString()))
                .andExpect(jsonPath("$.items[1].quantity").value(1));
        mvc.perform(get(B + "/top-titles").param("from", "2026-11-16").param("to", "2026-11-16").param("limit", "1")
                        .with(admin(C))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(99));
    }

    @Test
    void ordersCsvKhongCoThongTinKhachVaLocTheoKenh() throws Exception {
        var res = mvc.perform(get(B + "/orders.csv").param("from", "2026-11-16").param("to", "2026-11-17")
                .with(admin(A))).andExpect(status().isOk()).andReturn().getResponse();
        String csv = new String(res.getContentAsByteArray(), 3, res.getContentAsByteArray().length - 3,
                StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(4); // tiêu đề + 3 đơn của A (không có đơn của C)
        assertThat(csv).doesNotContain("Khach Bi Mat").doesNotContain("0900000000").doesNotContain("C-1");
        // created_at giảm dần: A-2 (23:40 VN ngày 16) trước A-1 (23:30) trước A-3
        assertThat(lines[1]).isEqualTo("A-2,ONLINE,CANCELLED,REFUNDED,2026-11-16T23:40:00+07:00,"
                + "2026-11-17T00:10:00+07:00,1,50000,0,0,0,50000");
        assertThat(lines[3]).startsWith("A-3,ONLINE,PENDING_CONFIRMATION,UNPAID,");
        var online = mvc.perform(get(B + "/orders.csv").param("from", "2026-11-16").param("to", "2026-11-17")
                .param("channel", "ONLINE").with(admin(A))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(new String(online.getContentAsByteArray(), StandardCharsets.UTF_8).split("\r\n")).hasSize(3);
    }

    @Test
    void stockCsvSapTheoAvailableTangVaCoLapTenant() throws Exception {
        var res = mvc.perform(get(B + "/stock.csv").with(admin(A))).andExpect(status().isOk()).andReturn()
                .getResponse();
        byte[] b = res.getContentAsByteArray();
        String[] lines = new String(b, 3, b.length - 3, StandardCharsets.UTF_8).split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[1]).startsWith(T1 + ",,,2,0,2,5,true,");
        assertThat(lines[2]).startsWith(T3 + ",,,50,0,50,5,false,");
        var low = mvc.perform(get(B + "/stock.csv").param("lowStockOnly", "true").with(admin(A)))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(new String(low.getContentAsByteArray(), StandardCharsets.UTF_8).split("\r\n")).hasSize(2);
        assertThat(res.getHeader("X-Report-Truncated")).isNull();
    }
}
