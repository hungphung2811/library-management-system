package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lms.sales.ordering.application.AnonymizeWalkInBatchUseCase;
import lms.sales.ordering.application.AnonymizeWalkInContactsUseCase;
import lms.sales.ordering.application.CreateReturnCommand;
import lms.sales.ordering.application.OrderResponse;
import lms.sales.ordering.application.OrderWorkflowUseCases;
import lms.sales.ordering.application.PlaceOnlineOrderCommand;
import lms.sales.ordering.application.PlaceOnlineOrderUseCase;
import lms.sales.ordering.application.PlacePosOrderCommand;
import lms.sales.ordering.application.PlacePosOrderUseCase;
import lms.sales.ordering.application.RefundUseCases;
import lms.sales.ordering.application.ShopOrderResponse;
import lms.sales.ordering.application.ShopOrderUseCases;
import lms.sales.ordering.application.CreateReturnUseCase;
import lms.sales.ordering.domain.Disposition;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.Recipient;
import lms.sales.ordering.infrastructure.config.AnonymizeWalkInJob;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.settings.application.UpdateSalesSettingsCommand;
import lms.sales.settings.application.UpdateSalesSettingsUseCase;
import lms.sales.shared.application.TenantDirectoryPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.AdjustStockUseCase;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.ReconcileStockUseCase;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IT pha 2 bằng vai trò sales_app (NOSUPERUSER NOBYPASSRLS) trên PostgreSQL thật: luồng online đầy đủ, hủy hoàn tồn,
 * khách A không thấy đơn khách B (và tenant B không thấy tenant A), trả hàng các ca, test đồng thời (đặt + hủy đồng
 * thời, hai phiếu trả đồng thời, hai người đánh dấu thu đồng thời) với kiểm reserved cuối = tổng đơn còn hiệu lực, và
 * job ẩn danh với đồng hồ giả hai tenant. Cần Docker: mvn -o -pl services/sales-service -am test -Dtest=SalesPhase2IT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44. Catalog được giả bằng CatalogLookupPort.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/sales",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.sales.events.enabled=false",
        "lms.sales.anonymize.enabled=false"})
@Testcontainers
class SalesPhase2IT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    static final Map<UUID, CatalogLookupPort.CatalogTitle> TITLES = new ConcurrentHashMap<>();

    @Autowired PlaceOnlineOrderUseCase placeOnline;
    @Autowired PlacePosOrderUseCase placePos;
    @Autowired OrderWorkflowUseCases workflow;
    @Autowired ShopOrderUseCases shopOrders;
    @Autowired CreateReturnUseCase createReturn;
    @Autowired RefundUseCases refunds;
    @Autowired RegisterStockItemUseCase registerItem;
    @Autowired AdjustStockUseCase adjust;
    @Autowired ReconcileStockUseCase reconcile;
    @Autowired GetSalesSettingsUseCase getSettings;
    @Autowired UpdateSalesSettingsUseCase updateSettings;
    @Autowired TenantDirectoryPort tenantDirectory;
    @Autowired AnonymizeWalkInBatchUseCase anonymizeBatch;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean CatalogLookupPort catalog;

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID staff = UUID.randomUUID();

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

    // ------------------------------------------------------------------ tiện ích

    private UUID stocked(UUID tenant, String name, long coverPrice, long stock) {
        UUID id = UUID.randomUUID();
        TITLES.put(id, new CatalogLookupPort.CatalogTitle(id, name, "Tac gia", "ISBN-" + id.toString().substring(0, 8),
                BigDecimal.valueOf(coverPrice)));
        TenantContext.runAs(tenant, () -> {
            registerItem.execute(tenant, staff, id, null, null);
            adjust.execute(tenant, staff, id, stock, "ton dau");
        });
        return id;
    }

    private ShopOrderResponse place(UUID tenant, UUID customer, UUID title, int qty, Fulfilment f,
                                    PaymentMethod pm) {
        return TenantContext.callAs(tenant, () -> placeOnline.place(new PlaceOnlineOrderCommand(tenant, customer,
                List.of(new PlaceOnlineOrderCommand.Line(title, qty)), f, pm,
                new Recipient("Nguyen Van An", "0912345678", "1 A, Quan 1, TP HCM"), UUID.randomUUID())));
    }

    private OrderResponse pos(UUID tenant, UUID title, int qty, String walkInName, String walkInPhone) {
        return TenantContext.callAs(tenant, () -> placePos.create(new PlacePosOrderCommand(tenant, staff,
                List.of(new PlacePosOrderCommand.Line(title, qty, null)), PaymentMethod.CASH, null, walkInName,
                walkInPhone, null, UUID.randomUUID())));
    }

    private void assertReconciled(UUID tenant, UUID title) {
        var r = TenantContext.callAs(tenant, () -> reconcile.execute(tenant, title));
        assertThat(r.consistent()).as("on_hand/reserved phải bằng tổng sổ cái: " + r).isTrue();
    }

    private long scalar(String sql) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private long reserved(UUID tenant, UUID title) throws SQLException {
        return scalar("select reserved from stock_items where tenant_id = '" + tenant + "' and title_id = '" + title
                + "'");
    }

    private long onHand(UUID tenant, UUID title) throws SQLException {
        return scalar("select on_hand from stock_items where tenant_id = '" + tenant + "' and title_id = '" + title
                + "'");
    }

    /** Chạy các việc cùng lúc (cùng vạch xuất phát); trả kết quả hoặc ngoại lệ từng việc. */
    private <T> List<Object> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> t : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        return t.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            go.countDown();
            List<Object> out = new ArrayList<>();
            for (Future<Object> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static ErrorCode code(Object result) {
        return result instanceof SalesException e ? e.code() : null;
    }

    // ------------------------------------------------------------------ luồng online

    @Test
    void luongOnlineDayDuDatGiuTonXacNhanThuHoanTatVaDoiSoat() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID title = stocked(tenantA, "Sach online", 100_000, 10);
        ShopOrderResponse o = place(tenantA, customer, title, 2, Fulfilment.PICKUP, PaymentMethod.BANK_TRANSFER);
        UUID id = o.order().id();
        assertThat(onHand(tenantA, title)).isEqualTo(10);
        assertThat(reserved(tenantA, title)).isEqualTo(2);

        TenantContext.runAs(tenantA, () -> {
            workflow.confirm(tenantA, staff, id, null, null);
            workflow.markReadyForPickup(tenantA, staff, id, null);
            assertThat(catchCode(() -> workflow.complete(tenantA, staff, id, null))).isEqualTo(ErrorCode.ORDER_NOT_PAID);
            workflow.markPaid(tenantA, staff, id, PaymentMethod.BANK_TRANSFER, "FT1", null);
            workflow.complete(tenantA, staff, id, null);
        });
        assertThat(onHand(tenantA, title)).isEqualTo(8);
        assertThat(reserved(tenantA, title)).isZero();
        assertThat(scalar("select count(*) from payment_records where order_id = '" + id + "'")).isEqualTo(1);
        assertThat(scalar("select count(*) from sales_audit_log where aggregate_id = '" + id
                + "' and action = 'ORDER_PAID' and actor_id = '" + staff + "'")).isEqualTo(1);
        assertReconciled(tenantA, title);
        assertThat(scalar("select count(*) from sales_tenants where tenant_id = '" + tenantA + "'")).isEqualTo(1);
    }

    private ErrorCode catchCode(Runnable r) {
        try {
            r.run();
            return null;
        } catch (SalesException e) {
            return e.code();
        }
    }

    @Test
    void huyHoanTonVaKhachKhongThayDonCuaNguoiKhacHayTenantKhac() throws Exception {
        UUID customerA = UUID.randomUUID();
        UUID customerB = UUID.randomUUID();
        UUID title = stocked(tenantA, "Sach huy", 100_000, 5);
        UUID id = place(tenantA, customerA, title, 3, Fulfilment.PICKUP, PaymentMethod.CASH).order().id();
        assertThat(reserved(tenantA, title)).isEqualTo(3);

        // khách khác cùng tenant, và tenant khác: 404
        assertThat(catchCode(() -> TenantContext.runAs(tenantA, () -> shopOrders.myOrder(tenantA, customerB, id))))
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
        assertThat(catchCode(() -> TenantContext.runAs(tenantB, () -> shopOrders.myOrder(tenantB, customerA, id))))
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
        assertThat(catchCode(() -> TenantContext.runAs(tenantA, () -> shopOrders.cancelMyOrder(tenantA, customerB,
                id, null)))).isEqualTo(ErrorCode.ORDER_NOT_FOUND);
        assertThat(TenantContext.callAs(tenantA, () -> shopOrders.myOrders(tenantA, customerB, 0, 10).total()))
                .isZero();
        assertThat(TenantContext.callAs(tenantA, () -> shopOrders.myOrders(tenantA, customerA, 0, 10).total()))
                .isEqualTo(1);

        TenantContext.runAs(tenantA, () -> shopOrders.cancelMyOrder(tenantA, customerA, id, "doi y"));
        assertThat(reserved(tenantA, title)).isZero();
        assertThat(onHand(tenantA, title)).isEqualTo(5);
        assertReconciled(tenantA, title);

        // Vai trò app không đặt tenant: fail-closed trên các bảng mới
        try (Connection c = AppRoleDatabase.appRole(postgres); Statement s = c.createStatement()) {
            for (String table : List.of("sales_orders", "sales_returns", "sales_return_lines", "refunds")) {
                try (ResultSet rs = s.executeQuery("select count(*) from " + table)) {
                    rs.next();
                    assertThat(rs.getLong(1)).as(table + " không tenant").isZero();
                }
            }
        }
    }

    @Test
    void khachKhongLayDuocDonQuaClientRequestIdCuaNguoiKhacVaKhongThayDonPos() throws Exception {
        UUID customerA = UUID.randomUUID();
        UUID customerB = UUID.randomUUID();
        UUID title = stocked(tenantA, "Sach khoa", 100_000, 20);
        UUID key = UUID.randomUUID();
        var recipient = new Recipient("Nguyen Van An", "0912345678", "1 A, Quan 1, TP HCM");
        java.util.function.BiFunction<UUID, UUID, ShopOrderResponse> order = (who, cid) -> TenantContext.callAs(
                tenantA, () -> placeOnline.place(new PlaceOnlineOrderCommand(tenantA, who,
                        List.of(new PlaceOnlineOrderCommand.Line(title, 1)), Fulfilment.PICKUP, PaymentMethod.CASH,
                        recipient, cid)));
        ShopOrderResponse a = order.apply(customerA, key);
        assertThat(order.apply(customerA, key).order().id()).isEqualTo(a.order().id()); // chính mình: đơn cũ
        assertThat(catchCode(() -> order.apply(customerB, key))).isEqualTo(ErrorCode.CLIENT_REQUEST_CONFLICT);
        assertThat(reserved(tenantA, title)).isEqualTo(1); // B không giữ thêm tồn
        assertThat(TenantContext.callAs(tenantA, () -> shopOrders.myOrders(tenantA, customerB, 0, 10).total()))
                .isZero();
        // đơn POS gắn tài khoản của A không hiện trong "đơn của tôi" và không mở được bằng id
        var posOrder = TenantContext.callAs(tenantA, () -> placePos.create(new PlacePosOrderCommand(tenantA, staff,
                List.of(new PlacePosOrderCommand.Line(title, 1, null)), PaymentMethod.CASH, customerA, null, null,
                null, UUID.randomUUID())));
        assertThat(catchCode(() -> TenantContext.runAs(tenantA, () -> shopOrders.myOrder(tenantA, customerA,
                posOrder.id())))).isEqualTo(ErrorCode.ORDER_NOT_FOUND);
        assertThat(TenantContext.callAs(tenantA, () -> shopOrders.myOrders(tenantA, customerA, 0, 10).total()))
                .isEqualTo(1);
    }

    @Test
    void giaoThatBaiHoanTonVaDonDaThuSinhKhoanHoanDanhDauDuoc() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID title = stocked(tenantA, "Sach giao", 100_000, 10);
        UUID id = place(tenantA, customer, title, 4, Fulfilment.DELIVERY, PaymentMethod.COD).order().id();
        TenantContext.runAs(tenantA, () -> {
            workflow.confirm(tenantA, staff, id, 20_000L, null);
            workflow.markShipping(tenantA, staff, id, null);
        });
        assertThat(onHand(tenantA, title)).isEqualTo(6);
        assertThat(reserved(tenantA, title)).isZero();
        TenantContext.runAs(tenantA, () -> {
            workflow.markPaid(tenantA, staff, id, PaymentMethod.COD, null, null);
            workflow.cancel(tenantA, staff, id, "Giao thất bại", null);
        });
        assertThat(onHand(tenantA, title)).isEqualTo(10);
        assertReconciled(tenantA, title);
        long refundId = scalar("select count(*) from refunds where order_id = '" + id + "' and status = 'PENDING' "
                + "and source = 'CANCELLATION' and amount = 420000");
        assertThat(refundId).isEqualTo(1);
        UUID rid = UUID.fromString(stringOf("select id from refunds where order_id = '" + id + "'"));
        TenantContext.runAs(tenantA, () -> refunds.markRefunded(tenantA, staff, rid, PaymentMethod.CASH, "RF", null));
        assertThat(stringOf("select payment_status from sales_orders where id = '" + id + "'")).isEqualTo("REFUNDED");
    }

    private String stringOf(String sql) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    // ------------------------------------------------------------------ trả hàng

    private CreateReturnCommand ret(UUID tenant, OrderResponse o, int qty, Disposition d, String lateReason) {
        return new CreateReturnCommand(tenant, staff, o.id(), List.of(new CreateReturnCommand.Line(
                o.lines().get(0).id(), qty, d)), "Sach loi", lateReason, UUID.randomUUID());
    }

    @Test
    void traHangRestockWriteOffQuaHanVaVuotSoDaMua() throws Exception {
        UUID title = stocked(tenantA, "Sach tra", 100_000, 10);
        OrderResponse o = pos(tenantA, title, 5, null, null);
        assertThat(onHand(tenantA, title)).isEqualTo(5);

        TenantContext.runAs(tenantA, () -> createReturn.create(ret(tenantA, o, 2, Disposition.RESTOCK, null)));
        assertThat(onHand(tenantA, title)).isEqualTo(7);
        TenantContext.runAs(tenantA, () -> createReturn.create(ret(tenantA, o, 1, Disposition.WRITE_OFF, null)));
        assertThat(onHand(tenantA, title)).isEqualTo(7); // RETURN +1 rồi WRITE_OFF -1
        assertThat(scalar("select count(*) from stock_ledger where type = 'WRITE_OFF' and tenant_id = '" + tenantA
                + "' and reason like 'Trả hàng hỏng%'")).isEqualTo(1);
        // vượt: đã trả 3 của 5, trả thêm 3
        assertThat(catchCode(() -> TenantContext.runAs(tenantA, () -> createReturn.create(ret(tenantA, o, 3,
                Disposition.RESTOCK, null))))).isEqualTo(ErrorCode.RETURN_QUANTITY_EXCEEDED);
        // quá hạn: lùi completed_at về 10 ngày trước
        exec("update sales_orders set completed_at = now() - interval '10 days' where id = '" + o.id() + "'");
        assertThat(catchCode(() -> TenantContext.runAs(tenantA, () -> createReturn.create(ret(tenantA, o, 1,
                Disposition.RESTOCK, null))))).isEqualTo(ErrorCode.RETURN_WINDOW_EXPIRED);
        TenantContext.runAs(tenantA, () -> createReturn.createLate(ret(tenantA, o, 1, Disposition.RESTOCK,
                "Khach xa")));
        assertThat(scalar("select count(*) from sales_returns where late and days_overdue between 3 and 4 and tenant_id = '"
                + tenantA + "'")).isEqualTo(1);
        assertThat(scalar("select count(*) from sales_audit_log where action = 'RETURN_LATE_APPROVED' "
                + "and actor_id = '" + staff + "'")).isEqualTo(1);
        // tổng hoàn không vượt tổng dòng (5 x 100.000)
        assertThat(scalar("select coalesce(sum(amount), 0) from refunds where order_id = '" + o.id() + "'"))
                .isLessThanOrEqualTo(500_000);
        assertReconciled(tenantA, title);
    }

    // ------------------------------------------------------------------ đồng thời

    @Test
    void datOnlineDongThoiVaHuyDongThoiReservedCuoiBangTongDonConHieuLuc() throws Exception {
        UUID title = stocked(tenantA, "Sach nong", 100_000, 8);
        // pha 1: 12 khách đặt cùng lúc 1 cuốn, kho có 8
        List<Callable<ShopOrderResponse>> placing = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID customer = UUID.randomUUID();
            placing.add(() -> place(tenantA, customer, title, 1, Fulfilment.PICKUP, PaymentMethod.CASH));
        }
        List<Object> placed = race(placing);
        List<UUID> ok = new ArrayList<>();
        for (Object r : placed) {
            if (r instanceof ShopOrderResponse s) {
                ok.add(s.order().id());
            } else {
                assertThat(code(r)).as("lỗi hợp lệ duy nhất khi hết hàng: " + r).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
            }
        }
        assertThat(ok).hasSize(8);
        assertThat(reserved(tenantA, title)).isEqualTo(8);

        // pha 2: hủy đồng thời từng đơn hai lần (16 lệnh) và đặt thêm 4 đơn cùng lúc
        List<Callable<Object>> mix = new ArrayList<>();
        List<UUID> toCancel = ok.subList(0, 4);
        for (UUID id : toCancel) {
            for (int k = 0; k < 2; k++) {
                mix.add(() -> TenantContext.callAs(tenantA, () -> workflow.cancel(tenantA, staff, id, "huy", null)));
            }
        }
        for (int i = 0; i < 4; i++) {
            UUID customer = UUID.randomUUID();
            mix.add(() -> place(tenantA, customer, title, 1, Fulfilment.PICKUP, PaymentMethod.CASH));
        }
        for (Object r : race(mix)) {
            if (r instanceof Exception) {
                assertThat(code(r)).as("chỉ được lỗi nghiệp vụ hợp lệ: " + r)
                        .isIn(ErrorCode.ORDER_STATE_CONFLICT, ErrorCode.INSUFFICIENT_STOCK);
            }
        }
        // mỗi đơn bị hủy đúng một lần; reserved = tổng số lượng các đơn còn hiệu lực (CHỜ XÁC NHẬN)
        long live = scalar("select coalesce(sum(l.quantity), 0) from sales_order_lines l join sales_orders o "
                + "on o.id = l.order_id where o.tenant_id = '" + tenantA + "' and l.title_id = '" + title
                + "' and o.status not in ('CANCELLED', 'COMPLETED')");
        assertThat(reserved(tenantA, title)).isEqualTo(live);
        assertThat(scalar("select count(*) from sales_orders o where o.status = 'CANCELLED' and o.tenant_id = '"
                + tenantA + "' and exists (select 1 from sales_order_lines l where l.order_id = o.id and l.title_id = '"
                + title + "')")).isEqualTo(4);
        assertThat(onHand(tenantA, title)).isEqualTo(8);
        assertReconciled(tenantA, title);
    }

    @Test
    void haiPhieuTraDongThoiKhongTraVuotSoDaMua() throws Exception {
        UUID title = stocked(tenantA, "Sach tra song song", 100_000, 10);
        OrderResponse o = pos(tenantA, title, 3, null, null);
        // 6 phiếu trả, mỗi phiếu 2 cuốn, chỉ đúng 1 phiếu (2 của 3 còn lại) thành công
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> TenantContext.callAs(tenantA, () -> createReturn.create(ret(tenantA, o, 2,
                    Disposition.RESTOCK, null))));
        }
        List<Object> results = race(tasks);
        long success = results.stream().filter(r -> !(r instanceof Exception)).count();
        results.stream().filter(r -> r instanceof Exception).forEach(r -> assertThat(code(r))
                .as("lỗi: " + r).isEqualTo(ErrorCode.RETURN_QUANTITY_EXCEEDED));
        assertThat(success).isEqualTo(1);
        assertThat(scalar("select coalesce(sum(rl.quantity), 0) from sales_return_lines rl where rl.tenant_id = '"
                + tenantA + "' and rl.order_line_id = '" + o.lines().get(0).id() + "'")).isEqualTo(2);
        assertThat(onHand(tenantA, title)).isEqualTo(9);
        assertReconciled(tenantA, title);
    }

    @Test
    void haiNguoiDanhDauThuCungLucChiMotThanhCongMotLoi409() throws Exception {
        UUID title = stocked(tenantA, "Sach thu", 100_000, 5);
        UUID id = place(tenantA, UUID.randomUUID(), title, 1, Fulfilment.PICKUP, PaymentMethod.BANK_TRANSFER)
                .order().id();
        TenantContext.runAs(tenantA, () -> workflow.confirm(tenantA, staff, id, null, null));
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tasks.add(() -> TenantContext.callAs(tenantA, () -> workflow.markPaid(tenantA, UUID.randomUUID(), id,
                    PaymentMethod.BANK_TRANSFER, "FT", null)));
        }
        List<Object> results = race(tasks);
        assertThat(results.stream().filter(r -> !(r instanceof Exception))).hasSize(1);
        assertThat(results.stream().filter(r -> r instanceof Exception).map(SalesPhase2IT::code))
                .containsExactly(ErrorCode.ORDER_STATE_CONFLICT);
        assertThat(scalar("select count(*) from payment_records where order_id = '" + id + "'")).isEqualTo(1);
        assertThat(scalar("select count(*) from sales_audit_log where aggregate_id = '" + id
                + "' and action = 'ORDER_PAID'")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ job ẩn danh

    @Test
    void jobAnDanhDongHoGiaHaiTenantThoiHanKhacNhau() throws Exception {
        UUID titleA = stocked(tenantA, "Sach an danh A", 100_000, 20);
        UUID titleB = stocked(tenantB, "Sach an danh B", 100_000, 20);
        // A giữ 6 tháng, B giữ 24 tháng (mặc định 12 bị ghi đè)
        TenantContext.runAs(tenantA, () -> updateSettings.execute(new UpdateSalesSettingsCommand(tenantA, staff, 500,
                true, 7, 0, 5, 6, getSettings.execute(tenantA).version())));
        TenantContext.runAs(tenantB, () -> updateSettings.execute(new UpdateSalesSettingsCommand(tenantB, staff, 500,
                true, 7, 0, 5, 24, getSettings.execute(tenantB).version())));

        OrderResponse oldA = pos(tenantA, titleA, 1, "Nguyen Van Cu", "0911111111");      // 8 tháng: quá hạn A
        OrderResponse freshA = pos(tenantA, titleA, 1, "Tran Thi Moi", "0922222222");     // 1 tháng: còn hạn
        OrderResponse openA = pos(tenantA, titleA, 1, "Le Van Do", "0933333333");         // chưa kết thúc
        OrderResponse oldB = pos(tenantB, titleB, 1, "Pham Van Cu B", "0944444444");      // 15 tháng: B 24 tháng
        TenantContext.runAs(tenantA, () -> {
        });
        exec("update sales_orders set completed_at = '2026-03-17T03:00:00Z' where id = '" + oldA.id() + "'");
        exec("update sales_orders set completed_at = '2026-10-17T03:00:00Z' where id = '" + freshA.id() + "'");
        exec("update sales_orders set status = 'CONFIRMED', completed_at = null where id = '" + openA.id() + "'");
        exec("update sales_orders set completed_at = '2025-08-17T03:00:00Z' where id = '" + oldB.id() + "'");

        Clock fake = Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"), ZoneOffset.UTC);
        var useCase = new AnonymizeWalkInContactsUseCase(getSettings, anonymizeBatch, fake, 500);
        var job = new AnonymizeWalkInJob(tenantDirectory, useCase);

        assertThat(tenantDirectory.listAll()).contains(tenantA, tenantB);
        long audits = scalar("select count(*) from sales_audit_log where action = 'WALK_IN_PII_ANONYMIZED'");
        int changed = job.runOnce();
        // chỉ đơn cũ của A (8 tháng > 6); đơn B 15 tháng < 24 nên giữ; đơn khác tenant không bị đụng
        assertThat(changed).isGreaterThanOrEqualTo(1);
        assertThat(stringOfNullable("select walk_in_name from sales_orders where id = '" + oldA.id() + "'")).isNull();
        assertThat(stringOfNullable("select walk_in_phone from sales_orders where id = '" + oldA.id() + "'")).isNull();
        assertThat(scalar("select count(*) from sales_orders where id = '" + oldA.id()
                + "' and pii_anonymized_at is not null and grand_total = 100000")).isEqualTo(1); // đơn và tiền giữ
        assertThat(stringOfNullable("select walk_in_name from sales_orders where id = '" + freshA.id() + "'"))
                .isEqualTo("Tran Thi Moi");
        assertThat(stringOfNullable("select walk_in_name from sales_orders where id = '" + openA.id() + "'"))
                .isEqualTo("Le Van Do");
        assertThat(stringOfNullable("select walk_in_name from sales_orders where id = '" + oldB.id() + "'"))
                .isEqualTo("Pham Van Cu B");
        assertThat(scalar("select count(*) from sales_audit_log where action = 'WALK_IN_PII_ANONYMIZED'"))
                .isGreaterThan(audits);
        // audit theo lô không chứa PII
        assertThat(scalar("select count(*) from sales_audit_log where action = 'WALK_IN_PII_ANONYMIZED' "
                + "and (data::text ilike '%Nguyen%' or data::text ilike '%0911111111%')")).isZero();

        // chạy lại: không đổi gì, không audit mới
        long auditsAfter = scalar("select count(*) from sales_audit_log where action = 'WALK_IN_PII_ANONYMIZED'");
        assertThat(job.runOnce()).isZero();
        assertThat(scalar("select count(*) from sales_audit_log where action = 'WALK_IN_PII_ANONYMIZED'"))
                .isEqualTo(auditsAfter);

        // sang cuối 2027-03: B (24 tháng) vẫn giữ đơn 2025-08; sang 2027-09: B ẩn danh đơn cũ
        var later = new AnonymizeWalkInContactsUseCase(getSettings, anonymizeBatch,
                Clock.fixed(Instant.parse("2027-09-17T03:00:00Z"), ZoneOffset.UTC), 500);
        new AnonymizeWalkInJob(tenantDirectory, later).runOnce();
        assertThat(stringOfNullable("select walk_in_name from sales_orders where id = '" + oldB.id() + "'")).isNull();
        // đơn chưa kết thúc vẫn không bị đụng dù đã rất lâu
        assertThat(stringOfNullable("select walk_in_name from sales_orders where id = '" + openA.id() + "'"))
                .isEqualTo("Le Van Do");
    }

    private String stringOfNullable(String sql) throws SQLException {
        return stringOf(sql);
    }
}
