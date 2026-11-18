package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lms.sales.invoicing.application.BuyerInput;
import lms.sales.invoicing.application.InvoiceQueryUseCases;
import lms.sales.invoicing.application.InvoiceRequestUseCases;
import lms.sales.invoicing.application.InvoiceSettingsUseCases;
import lms.sales.invoicing.application.InvoiceUseCases;
import lms.sales.invoicing.application.InvoiceView;
import lms.sales.ordering.application.CreateReturnCommand;
import lms.sales.ordering.application.CreateReturnUseCase;
import lms.sales.ordering.application.OrderQueryUseCases;
import lms.sales.ordering.application.OrderResponse;
import lms.sales.ordering.application.PaymentIntentUseCases;
import lms.sales.ordering.application.PlacePosOrderCommand;
import lms.sales.ordering.application.PlacePosOrderUseCase;
import lms.sales.ordering.domain.Disposition;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.AdjustStockUseCase;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.RegisterStockItemUseCase;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IT hóa đơn VAT nội bộ (ADR-0030 gói A) bằng vai trò sales_app (NOSUPERUSER NOBYPASSRLS) trên PostgreSQL thật: RLS hai
 * tenant, số hóa đơn liên tục khi phát hành song song, rollback không để lỗ số, trigger bất biến sau ISSUED, chỉ mục duy nhất
 * "một hóa đơn hiệu lực mỗi đơn", trả hàng sinh điều chỉnh cùng transaction, thay thế (thứ tự cập nhật rồi chèn). Cần Docker:
 * mvn -o -pl services/sales-service -am test -Dtest=InvoicingIT -Dsurefire.failIfNoSpecifiedTests=false
 * -DargLine=-Dapi.version=1.44. Catalog được giả bằng CatalogLookupPort.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/sales",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.sales.events.enabled=false"})
@Testcontainers
class InvoicingIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    static final Map<UUID, CatalogLookupPort.CatalogTitle> TITLES = new ConcurrentHashMap<>();

    @Autowired PlacePosOrderUseCase placePos;
    @Autowired PaymentIntentUseCases intentUseCases;
    @Autowired OrderQueryUseCases orderQueries;
    @Autowired RegisterStockItemUseCase registerItem;
    @Autowired AdjustStockUseCase adjust;
    @Autowired CreateReturnUseCase createReturn;
    @Autowired InvoiceUseCases invoices;
    @Autowired InvoiceQueryUseCases queries;
    @Autowired InvoiceRequestUseCases requests;
    @Autowired InvoiceSettingsUseCases settings;
    @Autowired PlatformTransactionManager txManager;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean CatalogLookupPort catalog;

    final UUID user = UUID.randomUUID();

    @BeforeEach
    void stubCatalog() {
        when(catalog.lookup(any())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            Map<UUID, CatalogLookupPort.CatalogTitle> out = new HashMap<>();
            ids.forEach(id -> {
                if (TITLES.containsKey(id)) {
                    out.put(id, TITLES.get(id));
                }
            });
            return out;
        });
    }

    // ---- dựng dữ liệu ----

    private UUID stocked(UUID tenant, String name, long coverPrice, long stock) {
        UUID id = UUID.randomUUID();
        TITLES.put(id, new CatalogLookupPort.CatalogTitle(id, name, "Tac gia", "ISBN-" + id.toString().substring(0, 8),
                BigDecimal.valueOf(coverPrice)));
        TenantContext.runAs(tenant, () -> {
            registerItem.execute(tenant, user, id, null, null);
            adjust.execute(tenant, user, id, stock, "ton dau");
        });
        return id;
    }

    /** POS hai bước: tạo đơn rồi xác nhận thu tay để đơn COMPLETED + PAID. */
    private OrderResponse sold(UUID tenant, UUID title, int qty) {
        return TenantContext.callAs(tenant, () -> {
            var r = placePos.createWithPayment(new PlacePosOrderCommand(tenant, user,
                    List.of(new PlacePosOrderCommand.Line(title, qty, null)), PaymentMethod.CASH, null, null, null,
                    null, UUID.randomUUID()));
            intentUseCases.confirm(tenant, user, r.paymentIntent().id(),
                    new PaymentIntentUseCases.ConfirmCommand(null, null));
            return orderQueries.get(tenant, r.order().id());
        });
    }

    private void configureSeller(UUID tenant) {
        TenantContext.runAs(tenant, () -> settings.update(new InvoiceSettingsUseCases.UpdateCommand(tenant, user,
                "Nhà sách " + tenant.toString().substring(0, 4), "0123456789", "1 Lê Lợi, Quận 1", "0900000000", null,
                "1", "C26TAA", false, 0)));
    }

    private InvoiceView issue(UUID tenant, UUID orderId) {
        return TenantContext.callAs(tenant, () -> invoices.issue(new InvoiceUseCases.IssueCommand(tenant, orderId, user,
                UUID.randomUUID())));
    }

    private static ErrorCode codeOf(Runnable r) {
        return org.junit.jupiter.api.Assertions.assertThrows(SalesException.class, r::run).code();
    }

    private static long scalar(String sql) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    // ---- RLS hai tenant ----

    @Test
    void cachLyHaiTenantMoiBangHoaDon() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID title = stocked(a, "Sach cua A", 100_000, 10);
        configureSeller(a);
        OrderResponse order = sold(a, title, 2);
        TenantContext.runAs(a, () -> requests.saveByStaff(a, user, order.id(),
                new BuyerInput(null, "Công ty A", "0123456789-001", "1 Nguyễn Huệ", "kt@a.vn")));
        InvoiceView inv = issue(a, order.id());

        for (String table : List.of("invoices", "invoice_lines", "invoice_requests", "invoice_settings")) {
            assertThat(scalar("select count(*) from " + table + " where tenant_id = '" + a + "'")).as(table + " của A")
                    .isPositive();
            assertThat(scalar("select count(*) from " + table + " where tenant_id = '" + b + "'")).as(table + " của B")
                    .isZero();
        }
        // B không thấy, không hủy, không lập, không đọc yêu cầu của A
        TenantContext.runAs(b, () -> {
            assertThat(codeOf(() -> queries.get(b, inv.id()))).isEqualTo(ErrorCode.INVOICE_NOT_FOUND);
            assertThat(codeOf(() -> queries.print(b, inv.id()))).isEqualTo(ErrorCode.INVOICE_NOT_FOUND);
            assertThat(queries.search(b, new InvoiceQueryUseCases.SearchQuery(null, null, null, null, null, 0, 20))
                    .total()).isZero();
            assertThat(codeOf(() -> invoices.cancel(new InvoiceUseCases.CancelCommand(b, inv.id(), user, "x"))))
                    .isEqualTo(ErrorCode.INVOICE_NOT_FOUND);
            assertThat(codeOf(() -> invoices.issue(new InvoiceUseCases.IssueCommand(b, order.id(), user,
                    UUID.randomUUID())))).isEqualTo(ErrorCode.ORDER_NOT_FOUND);
            assertThat(codeOf(() -> requests.get(b, order.id()))).isEqualTo(ErrorCode.ORDER_NOT_FOUND);
            assertThat(settings.get(b).complete()).isFalse();
        });
        // fail-closed: vai trò ứng dụng không đặt tenant thì không thấy gì và không chèn được
        try (Connection c = AppRoleDatabase.appRole(postgres); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("select count(*) from invoices")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
            assertThatThrownBy(() -> s.execute("insert into invoice_settings (tenant_id, seller_legal_name, "
                    + "seller_tax_code, seller_address, template_code, series) values ('" + b + "', 'x', "
                    + "'0123456789', 'y', '1', 'C')")).hasMessageContaining("row-level security");
        }
        // người mua của A có trong hóa đơn của A
        assertThat(TenantContext.callAs(a, () -> queries.get(a, inv.id())).buyer().company()).isEqualTo("Công ty A");
    }

    // ---- số hóa đơn ----

    @Test
    void phatHanhSongSongSoLienTucKhongTrungKhongLo() throws Exception {
        UUID t = UUID.randomUUID();
        UUID title = stocked(t, "Sach song song", 50_000, 100);
        configureSeller(t);
        int n = 12;
        List<UUID> orderIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            orderIds.add(sold(t, title, 1).id());
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (UUID id : orderIds) {
            futures.add(pool.submit(() -> {
                start.await();
                return issue(t, id).invoiceNo();
            }));
        }
        start.countDown();
        TreeSet<Long> numbers = new TreeSet<>();
        for (Future<Long> f : futures) {
            numbers.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(numbers).hasSize(n);
        assertThat(numbers.first()).isEqualTo(1L);
        assertThat(numbers.last()).isEqualTo((long) n);
        assertThat(scalar("select last_value from sales_counters where tenant_id = '" + t
                + "' and name like 'INVOICE:1:C26TAA:%'")).isEqualTo(n);
        assertThat(scalar("select count(distinct invoice_no) from invoices where tenant_id = '" + t + "'")).isEqualTo(n);
    }

    @Test
    void rollbackKhongDeLoSoVaKhongDeLaiHoaDon() throws Exception {
        UUID t = UUID.randomUUID();
        UUID title = stocked(t, "Sach rollback", 50_000, 10);
        configureSeller(t);
        OrderResponse o1 = sold(t, title, 1);
        OrderResponse o2 = sold(t, title, 1);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> TenantContext.runAs(t, () -> tx.executeWithoutResult(status -> {
            invoices.issue(new InvoiceUseCases.IssueCommand(t, o1.id(), user, UUID.randomUUID()));
            throw new IllegalStateException("hỏng sau khi cấp số");
        }))).isInstanceOf(IllegalStateException.class);
        assertThat(scalar("select count(*) from invoices where tenant_id = '" + t + "'")).isZero();
        // số 1 chưa bị tiêu: hóa đơn kế tiếp vẫn là số 1, rồi 2
        assertThat(issue(t, o1.id()).invoiceNo()).isEqualTo(1);
        assertThat(issue(t, o2.id()).invoiceNo()).isEqualTo(2);
    }

    // ---- bất biến và chỉ mục duy nhất ở DB ----

    @Test
    void triggerChanSuaVaXoaHoaDonDaPhatHanh() throws Exception {
        UUID t = UUID.randomUUID();
        UUID title = stocked(t, "Sach bat bien", 80_000, 10);
        configureSeller(t);
        InvoiceView inv = issue(t, sold(t, title, 1).id());
        String id = inv.id().toString();
        for (String bad : List.of("update invoices set grand_total = 1, total_before_vat = 1, vat_total = 0",
                "update invoices set buyer_name = 'Sửa lén'", "update invoices set seller_legal_name = 'X'",
                "update invoices set invoice_no = 99", "update invoices set status = 'DRAFT'",
                "update invoices set issued_at = now()")) {
            assertThatThrownBy(() -> exec(bad + " where id = '" + id + "'")).as(bad)
                    .hasMessageContaining("Hoa don da phat hanh");
        }
        assertThatThrownBy(() -> exec("delete from invoices where id = '" + id + "'"))
                .hasMessageContaining("Khong duoc xoa");
        assertThatThrownBy(() -> exec("update invoice_lines set unit_price = 1 where invoice_id = '" + id + "'"))
                .hasMessageContaining("bat bien");
        assertThatThrownBy(() -> exec("delete from invoice_lines where invoice_id = '" + id + "'"))
                .hasMessageContaining("bat bien");
        // cột tích hợp sửa được; hủy qua use case thì được và sau đó khóa luôn trạng thái
        exec("update invoices set external_status = 'SENT' where id = '" + id + "'");
        TenantContext.runAs(t, () -> invoices.cancel(new InvoiceUseCases.CancelCommand(t, inv.id(), user, "Hủy thử")));
        assertThat(scalar("select count(*) from invoices where id = '" + id + "' and status = 'CANCELLED'")).isEqualTo(1);
        assertThatThrownBy(() -> exec("update invoices set status = 'ISSUED' where id = '" + id + "'"))
                .hasMessageContaining("Hoa don da huy");
        assertThatThrownBy(() -> exec("update invoices set cancel_reason = 'đổi lý do' where id = '" + id + "'"))
                .hasMessageContaining("bat bien");
    }

    @Test
    void chiMucDuyNhatChanHaiHoaDonGocVaSoTrung() throws Exception {
        UUID t = UUID.randomUUID();
        UUID title = stocked(t, "Sach duy nhat", 80_000, 10);
        configureSeller(t);
        OrderResponse o = sold(t, title, 1);
        InvoiceView inv = issue(t, o.id());
        // hai hóa đơn ORIGINAL hiệu lực cho một đơn
        String clone = "insert into invoices (id, tenant_id, kind, status, order_id, order_no, template_code, series, "
                + "fiscal_year, invoice_no, issued_at, amount_in_words, seller_legal_name, seller_tax_code, "
                + "seller_address, prices_include_vat, total_before_vat, vat_total, grand_total, created_at) "
                + "select gen_random_uuid(), tenant_id, kind, status, order_id, order_no, template_code, series, "
                + "fiscal_year, %s, issued_at, amount_in_words, seller_legal_name, seller_tax_code, seller_address, "
                + "prices_include_vat, total_before_vat, vat_total, grand_total, created_at from invoices where id = '"
                + inv.id() + "'";
        assertThatThrownBy(() -> exec(clone.formatted("77"))).hasMessageContaining("uq_invoices_order_effective");
        // cùng số trong cùng (mẫu số, ký hiệu, năm) nhưng đơn khác
        UUID o2 = sold(t, title, 1).id();
        String clone2 = "insert into invoices (id, tenant_id, kind, status, order_id, order_no, template_code, series, "
                + "fiscal_year, invoice_no, issued_at, amount_in_words, seller_legal_name, seller_tax_code, "
                + "seller_address, prices_include_vat, total_before_vat, vat_total, grand_total, created_at) "
                + "select gen_random_uuid(), tenant_id, kind, status, '" + o2 + "', 'DH-X', template_code, series, "
                + "fiscal_year, invoice_no, issued_at, amount_in_words, seller_legal_name, seller_tax_code, "
                + "seller_address, prices_include_vat, total_before_vat, vat_total, grand_total, created_at "
                + "from invoices where id = '" + inv.id() + "'";
        assertThatThrownBy(() -> exec(clone2)).hasMessageContaining("uq_invoices_number");
        // CHECK: tổng không khớp
        assertThatThrownBy(() -> exec("update invoices set grand_total = grand_total + 1 where id = '" + inv.id() + "'"))
                .isInstanceOf(SQLException.class);
    }

    // ---- hủy, thay thế, điều chỉnh ----

    @Test
    void thayTheTrenPostgresHuyBanGocTruocKhiChenBanThayThe() {
        UUID t = UUID.randomUUID();
        UUID title = stocked(t, "Sach thay the", 80_000, 10);
        configureSeller(t);
        OrderResponse o = sold(t, title, 1);
        InvoiceView orig = issue(t, o.id());
        InvoiceView rep = TenantContext.callAs(t, () -> invoices.replace(new InvoiceUseCases.ReplaceCommand(t, orig.id(),
                user, "Sai thông tin", new BuyerInput(null, "Công ty B", "0123456789", "2 Hai Bà Trưng", null),
                UUID.randomUUID())));
        assertThat(rep.kind()).isEqualTo("REPLACEMENT");
        assertThat(rep.invoiceNo()).isEqualTo(2);
        assertThat(rep.originalInvoiceId()).isEqualTo(orig.id());
        assertThat(TenantContext.callAs(t, () -> queries.get(t, orig.id())).status()).isEqualTo("CANCELLED");
        var page = TenantContext.callAs(t, () -> queries.search(t, new InvoiceQueryUseCases.SearchQuery(null, null, null,
                null, "Công ty B", 0, 20)));
        assertThat(page.total()).isEqualTo(1);
        // tìm theo số hóa đơn và theo số đơn
        assertThat(TenantContext.callAs(t, () -> queries.search(t, new InvoiceQueryUseCases.SearchQuery(null, null,
                null, null, "2", 0, 20))).total()).isPositive();
        assertThat(TenantContext.callAs(t, () -> queries.search(t, new InvoiceQueryUseCases.SearchQuery(null, null,
                null, null, o.orderNo(), 0, 20))).total()).isEqualTo(2);
    }

    @Test
    void traHangSinhDieuChinhCungTransactionVaRollbackCaHai() throws Exception {
        UUID t = UUID.randomUUID();
        UUID title = stocked(t, "Sach tra hang", 33_335, 20);
        configureSeller(t);
        OrderResponse o = sold(t, title, 3);
        InvoiceView orig = issue(t, o.id());
        UUID line = o.lines().get(0).id();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // phiếu trả bị rollback sau khi đã sinh điều chỉnh: cả hai cùng mất
        assertThatThrownBy(() -> TenantContext.runAs(t, () -> tx.executeWithoutResult(s -> {
            createReturn.create(new CreateReturnCommand(t, user, o.id(), List.of(new CreateReturnCommand.Line(line, 1,
                    Disposition.RESTOCK)), "Sách lỗi", null, UUID.randomUUID()));
            throw new IllegalStateException("hỏng sau khi trả hàng");
        }))).isInstanceOf(IllegalStateException.class);
        assertThat(scalar("select count(*) from sales_returns where tenant_id = '" + t + "'")).isZero();
        assertThat(scalar("select count(*) from invoices where tenant_id = '" + t + "' and kind = 'ADJUSTMENT_DECREASE'"))
                .isZero();

        var r1 = TenantContext.callAs(t, () -> createReturn.create(new CreateReturnCommand(t, user, o.id(),
                List.of(new CreateReturnCommand.Line(line, 1, Disposition.RESTOCK)), "Sách lỗi", null, UUID.randomUUID())));
        var r2 = TenantContext.callAs(t, () -> createReturn.create(new CreateReturnCommand(t, user, o.id(),
                List.of(new CreateReturnCommand.Line(line, 2, Disposition.RESTOCK)), "Sách lỗi", null, UUID.randomUUID())));
        assertThat(scalar("select count(*) from invoices where tenant_id = '" + t + "' and kind = 'ADJUSTMENT_DECREASE' "
                + "and original_invoice_id = '" + orig.id() + "' and return_id is not null")).isEqualTo(2);
        assertThat(scalar("select sum(grand_total) from invoices where tenant_id = '" + t
                + "' and kind = 'ADJUSTMENT_DECREASE'")).isEqualTo(orig.grandTotal()).isEqualTo(r1.refundTotal() + r2.refundTotal());
        assertThat(scalar("select sum(vat_total) from invoices where tenant_id = '" + t
                + "' and kind = 'ADJUSTMENT_DECREASE'")).as("thuế điều chỉnh trả hết = thuế gốc").isEqualTo(orig.vatTotal());
        // hóa đơn gốc nguyên vẹn; không hủy/thay thế được
        InvoiceView after = TenantContext.callAs(t, () -> queries.get(t, orig.id()));
        assertThat(after.status()).isEqualTo("ISSUED");
        assertThat(after.adjustments()).hasSize(2);
        assertThat(codeOf(() -> TenantContext.runAs(t, () -> invoices.cancel(new InvoiceUseCases.CancelCommand(t,
                orig.id(), user, "x"))))).isEqualTo(ErrorCode.INVOICE_HAS_ADJUSTMENTS);
    }

    @Test
    void donKhongCoHoaDonTraHangVanThanhCongKhongSinhHoaDon() throws Exception {
        UUID t = UUID.randomUUID();
        UUID title = stocked(t, "Sach khong hoa don", 40_000, 10);
        OrderResponse o = sold(t, title, 2);
        TenantContext.runAs(t, () -> createReturn.create(new CreateReturnCommand(t, user, o.id(),
                List.of(new CreateReturnCommand.Line(o.lines().get(0).id(), 1, Disposition.RESTOCK)), "Lỗi", null,
                UUID.randomUUID())));
        assertThat(scalar("select count(*) from sales_returns where tenant_id = '" + t + "'")).isEqualTo(1);
        assertThat(scalar("select count(*) from invoices where tenant_id = '" + t + "'")).isZero();
    }
}
