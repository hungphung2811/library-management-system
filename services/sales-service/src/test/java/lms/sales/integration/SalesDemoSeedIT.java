package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan;
import lms.demo.runner.DemoSeedProperties;
import lms.demo.runner.DemoSeedRunner;
import lms.demo.runner.DemoSeedStep;
import lms.sales.contact.infrastructure.config.ShopContactAnonymizationJob;
import lms.sales.ordering.application.RefundRepository;
import lms.sales.ordering.application.SalesOrderRepository;
import lms.sales.ordering.application.SalesReturnRepository;
import lms.sales.ordering.infrastructure.config.AnonymizeWalkInJob;
import lms.sales.ordering.infrastructure.demo.OrderingDemoSeedStep;
import lms.sales.reporting.application.SalesStatsUseCases;
import lms.sales.settings.application.SalesSettingsPort;
import lms.sales.settings.infrastructure.demo.SettingsDemoSeedStep;
import lms.sales.shared.application.TenantDirectoryPort;
import lms.sales.shared.infrastructure.demo.DemoTimes;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.GoodsReceiptRepository;
import lms.sales.stock.application.StockPort;
import lms.sales.stock.application.SupplierRepository;
import lms.sales.stock.infrastructure.demo.StockDemoSeedStep;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionOperations;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cần Docker. Chạy: mvn -o -pl services/sales-service -am test -Dtest=SalesDemoSeedIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44. Seed dữ liệu demo (ADR-0018) bằng vai trò
 * sales_app dưới RLS thật, qua DemoSeedRunner (guard + TenantContext.callAs + các bước theo thứ tự). Nạp
 * application-demo-seed.yml qua spring.config.import (không bật profile, vì runner thật gọi System.exit) và tiến trình
 * chạy không web: kiểm cả cấu hình profile (không web, job ẩn danh tắt).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/sales",
        "spring.config.import=classpath:application-demo-seed.yml",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true"})
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SalesDemoSeedIT {

    static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000d3");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    @MockitoBean
    CatalogLookupPort catalog;

    @Autowired
    ApplicationContext context;
    @Autowired
    SalesSettingsPort settings;
    @Autowired
    SupplierRepository suppliers;
    @Autowired
    GoodsReceiptRepository receipts;
    @Autowired
    StockPort stock;
    @Autowired
    SalesOrderRepository orders;
    @Autowired
    SalesReturnRepository returns;
    @Autowired
    RefundRepository refunds;
    @Autowired
    TenantDirectoryPort directory;
    @Autowired
    TransactionOperations tx;
    @Autowired
    SalesStatsUseCases stats;

    @BeforeEach
    void stubCatalog() {
        when(catalog.lookup(any())).thenReturn(Map.of());
    }

    private List<DemoSeedStep> steps() {
        return List.of(new OrderingDemoSeedStep(orders, returns, refunds, directory, tx),
                new StockDemoSeedStep(suppliers, receipts, stock, tx), new SettingsDemoSeedStep(settings, tx));
    }

    private DemoSeedRunner runner(List<String> profiles, boolean enabled) {
        DemoSeedProperties props = new DemoSeedProperties(enabled, TENANT.toString(), "demo", false,
                DemoPlan.DEFAULT_SEED);
        return new DemoSeedRunner(props, profiles, steps(), Clock.system(DemoTimes.BUSINESS_ZONE), context, code -> { });
    }

    private long count(String sql) throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @Order(1)
    void guardTuChoiKhiProdHoacThieuCoVaKhongGhiGi() throws Exception {
        assertThat(runner(List.of("prod", "demo-seed"), true).execute()).isEqualTo(DemoSeedRunner.EXIT_REFUSED);
        assertThat(runner(List.of("dev", "demo-seed"), false).execute()).isEqualTo(DemoSeedRunner.EXIT_REFUSED);
        assertThat(count("select (select count(*) from sales_orders) + (select count(*) from stock_items) "
                + "+ (select count(*) from sales_settings)")).isZero();
    }

    @Test
    @Order(2)
    void tienTrinhKhongWebVaKhongCoJobAnDanh() {
        assertThat(context).isNotInstanceOf(org.springframework.web.context.WebApplicationContext.class);
        assertThat(context.getBeanNamesForType(AnonymizeWalkInJob.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ShopContactAnonymizationJob.class)).isEmpty();
    }

    @Test
    @Order(3)
    void seedDungSoLuongVaBatBien() throws Exception {
        assertThat(runner(List.of("dev", "demo-seed"), true).execute()).isEqualTo(DemoSeedRunner.EXIT_OK);
        assertCounts();
        // sổ cái cộng đúng bộ đếm, từng mặt hàng
        assertThat(count("select count(*) from stock_items i where i.on_hand <> (select coalesce(sum(delta_on_hand), 0) "
                + "from stock_ledger l where l.stock_item_id = i.id) or i.reserved <> (select coalesce(sum(delta_reserved), 0) "
                + "from stock_ledger l where l.stock_item_id = i.id)")).isZero();
        // tiền đơn: tổng dòng + ship = tổng đơn; bản ghi thu đúng đơn đã thanh toán và đủ grand_total
        assertThat(count("select count(*) from sales_orders o where o.grand_total <> o.shipping_fee + "
                + "(select sum(line_total) from sales_order_lines l where l.order_id = o.id)")).isZero();
        assertThat(count("select count(*) from sales_orders o where (o.paid_at is not null) <> exists "
                + "(select 1 from payment_records p where p.order_id = o.id)")).isZero();
        assertThat(count("select count(*) from sales_orders o join payment_records p on p.order_id = o.id "
                + "where p.amount <> o.grand_total")).isZero();
        assertThat(count("select count(*) from sales_orders where payment_status in ('PAID','PARTIALLY_REFUNDED','REFUNDED') "
                + "and paid_at is null")).isZero();
        assertThat(count("select count(*) from sales_orders where payment_status = 'UNPAID' and paid_at is not null")).isZero();
        // đơn thanh toán rồi hủy giữ paid_at
        assertThat(count("select count(*) from sales_orders where status = 'CANCELLED' and paid_at is not null")).isEqualTo(1);
        assertThat(count("select count(*) from sales_orders where status = 'CANCELLED' and paid_at is null")).isEqualTo(2);
        // không mốc nào ở tương lai
        assertThat(count("select count(*) from sales_orders where created_at > now() or paid_at > now() or completed_at > now() "
                + "or cancelled_at > now() or confirmed_at > now()")).isZero();
        assertThat(count("select count(*) from refunds where created_at > now() or refunded_at > now()")).isZero();
        assertThat(count("select count(*) from stock_ledger where occurred_at > now()")).isZero();
        // dữ liệu vừa seed không bị ẩn danh (job tắt)
        assertThat(count("select count(*) from sales_orders where pii_anonymized_at is not null")).isZero();
        // danh bạ tenant cho job ẩn danh ở chế độ phục vụ bình thường
        assertThat(count("select count(*) from sales_tenants where tenant_id = '" + TENANT + "'")).isEqualTo(1);
    }

    private void assertCounts() throws Exception {
        assertThat(count("select count(*) from sales_settings where tenant_id = '" + TENANT + "' and low_stock_threshold = 5"))
                .isEqualTo(1);
        assertThat(count("select count(*) from suppliers")).isEqualTo(2);
        assertThat(count("select count(*) from goods_receipts where status = 'CONFIRMED' and confirmed_at is not null"))
                .isEqualTo(2);
        DemoPlan plan = DemoPlan.generate(LocalDate.now(DemoTimes.BUSINESS_ZONE));
        assertThat(count("select count(*) from stock_items")).isEqualTo(plan.stockItems().size());
        // mô hình tác phẩm (ADR-0026): mọi mặt hàng có snapshot work_id; 64 ấn bản gốc khớp công thức backfill md5; có tác phẩm nhiều ấn bản
        assertThat(count("select count(*) from stock_items where work_id is null")).isZero();
        assertThat(count("select count(*) from stock_items where work_id = md5('lms-work:' || title_id::text)::uuid"))
                .isEqualTo(plan.stockItems().stream().filter(i -> i.workId().equals(DemoIds.legacyWork(i.titleId()))).count());
        assertThat(count("select count(*) from (select work_id from stock_items group by work_id having count(*) >= 2) g"))
                .isGreaterThanOrEqualTo(2);
        assertThat(count("select count(*) from sales_orders where order_no like 'DEMO-%'")).isEqualTo(42);
        assertThat(count("select count(*) from sales_orders")).isEqualTo(42);
        assertThat(count("select count(*) from sales_returns")).isEqualTo(3);
        assertThat(count("select count(*) from refunds")).isEqualTo(4);
        assertThat(count("select count(*) from refunds where status = 'PENDING'")).isEqualTo(1);
        assertThat(count("select count(*) from sales_orders where channel = 'POS'")).isPositive();
        assertThat(count("select count(*) from sales_orders where channel = 'ONLINE'")).isPositive();
        assertThat(count("select count(distinct status) from sales_orders")).isEqualTo(6);
    }

    @Test
    @Order(4)
    void chayLaiKhongNhanDoiKhongLoi() throws Exception {
        long ledger = count("select count(*) from stock_ledger");
        long lines = count("select count(*) from sales_order_lines");
        assertThat(runner(List.of("dev", "demo-seed"), true).execute()).isEqualTo(DemoSeedRunner.EXIT_OK);
        assertCounts();
        assertThat(count("select count(*) from stock_ledger")).isEqualTo(ledger);
        assertThat(count("select count(*) from sales_order_lines")).isEqualTo(lines);
        assertThat(count("select count(*) from sales_tenants")).isEqualTo(1);
    }

    @Test
    @Order(5)
    void thongKeTrenDuLieuSeedVaCoLapTenant() throws Exception {
        var summary = TenantContext.callAs(TENANT, () -> stats.summary(TENANT, null));
        assertThat(summary.lowStockCount()).isEqualTo(DemoPlan.LOW_STOCK_ITEMS);
        assertThat(summary.lowStockItems()).hasSize(DemoPlan.LOW_STOCK_ITEMS);
        assertThat(summary.lowStockItems()).anyMatch(i -> i.available() == 0);
        assertThat(summary.pendingOnlineOrders()).isEqualTo(count(
                "select count(*) from sales_orders where channel = 'ONLINE' and status = 'PENDING_CONFIRMATION'"));
        // doanh thu theo dòng tiền: tổng đã thanh toán, trừ các khoản hoàn REFUNDED
        LocalDate today = LocalDate.now(DemoTimes.BUSINESS_ZONE);
        var trend = TenantContext.callAs(TENANT, () -> stats.revenueTrend(TENANT, today.minusDays(40), today));
        assertThat(trend.totals().gross()).isEqualTo(count("select coalesce(sum(grand_total), 0) from sales_orders "
                + "where paid_at is not null"));
        assertThat(trend.totals().refunds()).isEqualTo(count("select coalesce(sum(amount), 0) from refunds "
                + "where status = 'REFUNDED'"));
        assertThat(trend.totals().net()).isEqualTo(trend.totals().gross() - trend.totals().refunds());
        assertThat(TenantContext.callAs(TENANT, () -> stats.topSold(TENANT, today.minusDays(40), today, 5))).isNotEmpty();
        // tenant khác không thấy gì
        assertThat(TenantContext.callAs(OTHER, () -> stats.summary(OTHER, null)).lowStockCount()).isZero();
        assertThat(TenantContext.callAs(OTHER, () -> stats.revenueTrend(OTHER, today.minusDays(40), today)).totals().gross())
                .isZero();
        // RLS fail-closed: vai trò ứng dụng không đặt tenant thì không thấy dòng nào
        try (Connection c = AppRoleDatabase.appRole(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from sales_orders")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }
    }
}
