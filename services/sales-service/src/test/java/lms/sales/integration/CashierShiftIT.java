package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManagerFactory;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lms.sales.ordering.application.PaymentIntentUseCases;
import lms.sales.ordering.application.PlacePosOrderCommand;
import lms.sales.ordering.application.PlacePosOrderUseCase;
import lms.sales.ordering.application.PosOrderResult;
import lms.sales.ordering.application.RefundUseCases;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.shift.application.CashierShiftAdminUseCases;
import lms.sales.shift.application.CashierShiftRepository;
import lms.sales.shift.application.CashierShiftUseCases;
import lms.sales.shift.application.ShiftDetailView;
import lms.sales.shift.application.ShiftSettingsUseCases;
import lms.sales.shift.application.ShiftView;
import lms.sales.shift.domain.VarianceStatus;
import lms.sales.stock.application.AdjustStockUseCase;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.RegisterStockItemUseCase;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.hibernate.SessionFactory;
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
 * IT chốt ca thu ngân (ADR-0030 gói B) bằng vai trò sales_app (NOSUPERUSER NOBYPASSRLS) trên PostgreSQL thật: tiền mặt dự kiến
 * theo ca, tiền vào ca của người bấm xác nhận, hoàn tiền mặt, RLS hai tenant, chỉ mục duy nhất một ca OPEN mỗi thu ngân
 * (hai yêu cầu mở đồng thời chỉ một thành công), cờ chặn bán, lệnh chờ chặn đóng ca, số câu SQL báo cáo ca không tăng theo số đơn.
 * Cần Docker: mvn -o -pl services/sales-service -am test -Dtest=CashierShiftIT -Dsurefire.failIfNoSpecifiedTests=false
 * -DargLine=-Dapi.version=1.44.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/sales",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.sales.events.enabled=false",
        "lms.sales.anonymize.enabled=false"})
@Testcontainers
class CashierShiftIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    static final Map<UUID, CatalogLookupPort.CatalogTitle> TITLES = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired PlacePosOrderUseCase placePos;
    @Autowired PaymentIntentUseCases intentUseCases;
    @Autowired RefundUseCases refunds;
    @Autowired RegisterStockItemUseCase registerItem;
    @Autowired AdjustStockUseCase adjust;
    @Autowired CashierShiftUseCases shifts;
    @Autowired CashierShiftAdminUseCases adminShifts;
    @Autowired ShiftSettingsUseCases shiftSettings;
    @Autowired CashierShiftRepository shiftRepository;
    @Autowired EntityManagerFactory emf;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean CatalogLookupPort catalog;

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID cashier1 = UUID.randomUUID();
    final UUID cashier2 = UUID.randomUUID();
    final UUID manager = UUID.randomUUID();

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

    private UUID stocked(UUID tenant, long coverPrice, long stock) {
        UUID id = UUID.randomUUID();
        TITLES.put(id, new CatalogLookupPort.CatalogTitle(id, "Sach ca " + id.toString().substring(0, 6), "Tac gia",
                "ISBN-" + id.toString().substring(0, 8), BigDecimal.valueOf(coverPrice)));
        TenantContext.runAs(tenant, () -> {
            registerItem.execute(tenant, manager, id, null, null);
            adjust.execute(tenant, manager, id, stock, "ton dau");
        });
        return id;
    }

    private ShiftView open(UUID tenant, UUID cashier, long openingFloat) {
        return TenantContext.callAs(tenant, () -> shifts.open(tenant, cashier,
                new CashierShiftUseCases.OpenCommand(openingFloat, null, UUID.randomUUID())));
    }

    private PosOrderResult place(UUID tenant, UUID by, UUID title, int qty, PaymentMethod method) {
        return TenantContext.callAs(tenant, () -> placePos.createWithPayment(new PlacePosOrderCommand(tenant, by,
                List.of(new PlacePosOrderCommand.Line(title, qty, null)), method, null, null, null, null,
                UUID.randomUUID())));
    }

    private void confirm(UUID tenant, UUID actor, PosOrderResult sale) {
        TenantContext.runAs(tenant, () -> intentUseCases.confirm(tenant, actor, sale.paymentIntent().id(),
                new PaymentIntentUseCases.ConfirmCommand(null, "IT-REF")));
    }

    private UUID pendingRefund(UUID tenant, UUID orderId, long amount) throws SQLException {
        UUID id = UUID.randomUUID();
        exec("insert into refunds (id, tenant_id, order_id, source, amount, status) values ('" + id + "', '" + tenant
                + "', '" + orderId + "', 'CANCELLATION', " + amount + ", 'PENDING')");
        return id;
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

    private static ErrorCode codeOf(Runnable r) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(SalesException.class, r::run).code();
    }

    // ------------------------------------------------------------------ test

    @Test
    void tienMatDuKienTinhDungTheoCaTrenPostgres() throws Exception {
        UUID title = stocked(tenantA, 100_000, 100);
        ShiftView s1 = open(tenantA, cashier1, 200_000);
        ShiftView s2 = open(tenantA, cashier2, 0);

        PosOrderResult cashBy2 = place(tenantA, cashier1, title, 2, PaymentMethod.CASH); // cashier1 tạo, cashier2 thu
        PosOrderResult card = place(tenantA, cashier1, title, 1, PaymentMethod.CARD); // thẻ: không vào tiền mặt
        PosOrderResult cashBy1 = place(tenantA, cashier1, title, 1, PaymentMethod.CASH);
        confirm(tenantA, cashier2, cashBy2);
        confirm(tenantA, cashier1, card);
        confirm(tenantA, cashier1, cashBy1);
        long cash2 = cashBy2.order().grandTotal();
        long cash1 = cashBy1.order().grandTotal();
        long cardAmount = card.order().grandTotal();

        // hoàn tiền mặt bởi cashier1 (đơn do cashier2 thu): vào ca của cashier1
        UUID refundId = pendingRefund(tenantA, cashBy2.order().id(), 30_000);
        TenantContext.runAs(tenantA, () -> refunds.markRefunded(tenantA, cashier1, refundId, PaymentMethod.CASH,
                null, null));

        assertThat(scalar("select count(*) from payment_records where shift_id = '" + s2.id() + "'")).isEqualTo(1);
        assertThat(scalar("select count(*) from payment_records where shift_id = '" + s1.id() + "'")).isEqualTo(2);
        assertThat(scalar("select count(*) from payment_intents where shift_id = '" + s1.id() + "'")).isEqualTo(3);
        assertThat(scalar("select count(*) from refunds where shift_id = '" + s1.id() + "'")).isEqualTo(1);

        ShiftDetailView live = TenantContext.callAs(tenantA, () -> shifts.current(tenantA, cashier1));
        assertThat(live.provisionalExpectedCash()).isEqualTo(200_000 + cash1 - 30_000);
        assertThat(live.report().line("CARD").collected()).isEqualTo(cardAmount);
        assertThat(live.report().line("CASH").refunded()).isEqualTo(30_000);

        ShiftView closed1 = TenantContext.callAs(tenantA, () -> shifts.close(tenantA, cashier1,
                new CashierShiftUseCases.CloseCommand(200_000 + cash1 - 30_000, null)));
        assertThat(closed1.expectedCash()).isEqualTo(200_000 + cash1 - 30_000);
        assertThat(closed1.varianceStatus()).isEqualTo(VarianceStatus.NONE);

        // ca 2: tiền thu hộ đơn của cashier1, tổng đúng
        assertThat(codeOf(() -> TenantContext.runAs(tenantA, () -> shifts.close(tenantA, cashier2,
                new CashierShiftUseCases.CloseCommand(cash2 - 5_000, null))))).isEqualTo(
                ErrorCode.SHIFT_VARIANCE_REASON_REQUIRED);
        ShiftView closed2 = TenantContext.callAs(tenantA, () -> shifts.close(tenantA, cashier2,
                new CashierShiftUseCases.CloseCommand(cash2 - 5_000, "Thiếu 5.000")));
        assertThat(closed2.expectedCash()).isEqualTo(cash2);
        assertThat(closed2.variance()).isEqualTo(-5_000);
        assertThat(closed2.phase()).isEqualTo("PENDING_APPROVAL");

        ShiftView approved = TenantContext.callAs(tenantA, () -> adminShifts.approveVariance(tenantA, manager,
                s2.id(), "ok"));
        assertThat(approved.varianceStatus()).isEqualTo(VarianceStatus.APPROVED);

        // sau khi đóng: mở lại có lý do, số liệu đóng bị xóa, đóng lại ra số liệu mới
        ShiftView reopened = TenantContext.callAs(tenantA, () -> adminShifts.reopen(tenantA, manager, s2.id(),
                "Đếm nhầm"));
        assertThat(reopened.reopenCount()).isEqualTo(1);
        assertThat(reopened.expectedCash()).isNull();
        ShiftView again = TenantContext.callAs(tenantA, () -> shifts.close(tenantA, cashier2,
                new CashierShiftUseCases.CloseCommand(cash2, null)));
        assertThat(again.variance()).isZero();
        assertThat(scalar("select count(*) from cashier_shift_events where shift_id = '" + s2.id() + "'"))
                .isEqualTo(5); // OPENED, CLOSED, VARIANCE_APPROVED, REOPENED, CLOSED
    }

    @Test
    void haiTenantKhongThayCaCuaNhauVaCungMaThuNganMoDuocCaRieng() throws Exception {
        ShiftView a = open(tenantA, cashier1, 100_000);
        // tenant B không thấy ca của A (RLS + @TenantId)
        assertThat(TenantContext.callAs(tenantB, () -> shiftRepository.findById(tenantB, a.id()))).isEmpty();
        assertThat(TenantContext.callAs(tenantB, () -> adminShifts.search(tenantB,
                new CashierShiftRepository.Criteria(null, null, null, null, null), 0, 50)).total()).isZero();
        assertThat(codeOf(() -> TenantContext.runAs(tenantB, () -> adminShifts.get(tenantB, a.id()))))
                .isEqualTo(ErrorCode.SHIFT_NOT_FOUND);
        assertThat(codeOf(() -> TenantContext.runAs(tenantB, () -> adminShifts.reopen(tenantB, manager, a.id(), "x"))))
                .isEqualTo(ErrorCode.SHIFT_NOT_FOUND);
        // cùng userId ở tenant B mở ca riêng được; tenant A có thấy ca của B không
        ShiftView b = open(tenantB, cashier1, 0);
        assertThat(b.id()).isNotEqualTo(a.id());
        assertThat(TenantContext.callAs(tenantA, () -> shiftRepository.findById(tenantA, b.id()))).isEmpty();
        // cấu hình theo tenant
        TenantContext.runAs(tenantA, () -> shiftSettings.update(tenantA, manager, true, 7_000, 0));
        assertThat(TenantContext.callAs(tenantB, () -> shiftSettings.get(tenantB)).requireOpenShiftForPos()).isFalse();
        assertThat(TenantContext.callAs(tenantA, () -> shiftSettings.get(tenantA)).varianceThreshold())
                .isEqualTo(7_000);
    }

    @Test
    void chiMucDuyNhatMotCaOpenMoiThuNganVaHaiYeuCauDongThoiChiMotThanhCong() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID cashier = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> {
                    go.await();
                    try {
                        return TenantContext.callAs(tenant, () -> shifts.open(tenant, cashier,
                                new CashierShiftUseCases.OpenCommand(0, null, UUID.randomUUID())));
                    } catch (Exception e) {
                        return e;
                    }
                });
            }
            List<Future<Object>> futures = new ArrayList<>();
            tasks.forEach(t -> futures.add(pool.submit(t)));
            go.countDown();
            int ok = 0;
            int conflicts = 0;
            for (Future<Object> f : futures) {
                Object r = f.get(60, TimeUnit.SECONDS);
                if (r instanceof ShiftView) {
                    ok++;
                } else if (r instanceof SalesException e && e.code() == ErrorCode.SHIFT_ALREADY_OPEN) {
                    conflicts++;
                } else {
                    throw new AssertionError("kết quả bất ngờ: " + r);
                }
            }
            assertThat(ok).as("đúng một yêu cầu mở được ca").isEqualTo(1);
            assertThat(conflicts).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(scalar("select count(*) from cashier_shifts where tenant_id = '" + tenant + "' and status = 'OPEN'"))
                .isEqualTo(1);
        // chỉ mục duy nhất một phần chặn cả khi chèn thẳng bằng SQL
        assertThatThrownBy(() -> exec("insert into cashier_shifts (id, tenant_id, shift_no, cashier_user_id, status, "
                + "opened_at, opening_float, client_request_id) values ('" + UUID.randomUUID() + "', '" + tenant
                + "', 'CA-X', '" + cashier + "', 'OPEN', now(), 0, '" + UUID.randomUUID() + "')"))
                .hasMessageContaining("uq_cashier_shifts_open");
        // ca đã đóng không chiếm chỉ mục: đóng rồi mở lại ca mới được
        TenantContext.runAs(tenant, () -> shifts.close(tenant, cashier, new CashierShiftUseCases.CloseCommand(0, null)));
        assertThat(open(tenant, cashier, 0).id()).isNotNull();
    }

    @Test
    void coBatChanBanKhiChuaMoCaVaTatThiBanBinhThuong() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID title = stocked(tenant, 50_000, 50);
        // cờ tắt (mặc định): bán được, tiền không vào ca nào
        PosOrderResult free = place(tenant, cashier1, title, 1, PaymentMethod.CASH);
        confirm(tenant, cashier1, free);
        assertThat(scalar("select count(*) from payment_records where tenant_id = '" + tenant
                + "' and shift_id is null")).isEqualTo(1);
        // bật cờ
        TenantContext.runAs(tenant, () -> shiftSettings.update(tenant, manager, true, 0, 0));
        assertThat(codeOf(() -> place(tenant, cashier1, title, 1, PaymentMethod.CASH))).isEqualTo(ErrorCode.SHIFT_NOT_OPEN);
        ShiftView s = open(tenant, cashier1, 0);
        PosOrderResult ok = place(tenant, cashier1, title, 1, PaymentMethod.CASH);
        confirm(tenant, cashier1, ok);
        assertThat(scalar("select count(*) from payment_records where shift_id = '" + s.id() + "'")).isEqualTo(1);
        assertThat(codeOf(() -> place(tenant, cashier2, title, 1, PaymentMethod.CASH))).isEqualTo(ErrorCode.SHIFT_NOT_OPEN);
        // đơn tạo trước khi bật cờ, xác nhận khi cashier2 chưa có ca: bị chặn
        PosOrderResult byCashier1 = place(tenant, cashier1, title, 1, PaymentMethod.CASH);
        assertThat(codeOf(() -> confirm(tenant, cashier2, byCashier1))).isEqualTo(ErrorCode.SHIFT_NOT_OPEN);
    }

    @Test
    void lenhThuTayChoChanDongCaDenKhiXacNhan() {
        UUID tenant = UUID.randomUUID();
        UUID title = stocked(tenant, 80_000, 50);
        open(tenant, cashier1, 0);
        PosOrderResult pending = place(tenant, cashier1, title, 1, PaymentMethod.CASH);
        SalesException e = org.assertj.core.api.Assertions.catchThrowableOfType(SalesException.class,
                () -> TenantContext.runAs(tenant, () -> shifts.close(tenant, cashier1,
                        new CashierShiftUseCases.CloseCommand(0, null))));
        assertThat(e.code()).isEqualTo(ErrorCode.SHIFT_HAS_PENDING_PAYMENTS);
        confirm(tenant, cashier1, pending);
        ShiftView closed = TenantContext.callAs(tenant, () -> shifts.close(tenant, cashier1,
                new CashierShiftUseCases.CloseCommand(pending.order().grandTotal(), null)));
        assertThat(closed.variance()).isZero();
    }

    @Test
    void soCauSqlBaoCaoCaKhongTangTheoSoDon() {
        UUID tenant = UUID.randomUUID();
        UUID title = stocked(tenant, 10_000, 500);
        ShiftView s = open(tenant, cashier1, 0);
        SessionFactory sf = emf.unwrap(SessionFactory.class);
        sf.getStatistics().setStatisticsEnabled(true);

        confirm(tenant, cashier1, place(tenant, cashier1, title, 1, PaymentMethod.CASH));
        long few = statementsForDetail(sf, tenant, s.id());
        for (int i = 0; i < 8; i++) {
            confirm(tenant, cashier1, place(tenant, cashier1, title, 1, i % 2 == 0 ? PaymentMethod.CASH
                    : PaymentMethod.CARD));
        }
        long many = statementsForDetail(sf, tenant, s.id());
        assertThat(many).as("báo cáo ca: số câu SQL cố định, không theo số đơn (1 đơn: " + few + ", 9 đơn: " + many + ")")
                .isEqualTo(few);
        ShiftDetailView d = TenantContext.callAs(tenant, () -> adminShifts.get(tenant, s.id()));
        assertThat(d.report().orderCount()).isEqualTo(9);
    }

    private long statementsForDetail(SessionFactory sf, UUID tenant, UUID shiftId) {
        long before = sf.getStatistics().getPrepareStatementCount();
        TenantContext.runAs(tenant, () -> adminShifts.get(tenant, shiftId));
        return sf.getStatistics().getPrepareStatementCount() - before;
    }
}
