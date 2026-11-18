package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lms.sales.ordering.application.ExpireOnlineReservationsUseCase;
import lms.sales.ordering.application.OnlineReservationExpiry;
import lms.sales.ordering.application.OrderWorkflowUseCases;
import lms.sales.ordering.application.PlaceOnlineOrderCommand;
import lms.sales.ordering.application.PlaceOnlineOrderUseCase;
import lms.sales.ordering.application.SalesOrderRepository;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.Recipient;
import lms.sales.settings.application.OrderAbusePolicyUseCases;
import lms.sales.settings.domain.OrderAbusePolicy;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IT chống lạm dụng đặt hàng online trên PostgreSQL thật bằng vai trò sales_app (NOBYPASSRLS): nhiều luồng đồng thời
 * không bán vượt tồn, không vượt trần đơn chờ của một khách (khóa tư vấn theo khách), cùng clientRequestId chỉ tạo một
 * đơn, worker hết hạn chạy song song nhả kho đúng một lần, xác nhận đua với hết hạn không để lại trạng thái lệch, và
 * tenant này không động tới đơn/tồn của tenant khác. Cần Docker: mvn -o -pl services/sales-service -am test
 * -Dtest=OrderAbuseIT -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/sales",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.sales.events.enabled=false",
        "app.payments.expire-job-enabled=false",
        "lms.sales.abuse.reservation-expiry-job-enabled=false",
        "lms.cache.enabled=false",
        "lms.ratelimit.enabled=false"})
@Testcontainers
class OrderAbuseIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    static final Map<UUID, CatalogLookupPort.CatalogTitle> TITLES = new ConcurrentHashMap<>();

    @Autowired PlaceOnlineOrderUseCase place;
    @Autowired ExpireOnlineReservationsUseCase expire;
    @Autowired OnlineReservationExpiry expiry;
    @Autowired OrderWorkflowUseCases workflow;
    @Autowired OrderAbusePolicyUseCases policies;
    @Autowired SalesOrderRepository orders;
    @Autowired RegisterStockItemUseCase registerItem;
    @Autowired AdjustStockUseCase adjust;
    @Autowired ReconcileStockUseCase reconcile;
    @Autowired PlatformTransactionManager txManager;

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

    // ===== Hỗ trợ =====

    private UUID stocked(UUID tenant, long stock) {
        UUID id = UUID.randomUUID();
        TITLES.put(id, new CatalogLookupPort.CatalogTitle(id, "Sach " + id.toString().substring(0, 6), "Tac gia",
                "ISBN-" + id.toString().substring(0, 8), BigDecimal.valueOf(100_000)));
        TenantContext.runAs(tenant, () -> new TransactionTemplate(txManager).executeWithoutResult(t -> {
            registerItem.execute(tenant, staff, id, null, null);
            adjust.execute(tenant, staff, id, stock, "ton dau");
        }));
        return id;
    }

    private void setPolicy(UUID tenant, int minutes, int pending, int perDay) {
        TenantContext.runAs(tenant, () -> policies.update(tenant, staff, new OrderAbusePolicy.Values(minutes, pending,
                perDay, 50, 100, 5, 300), policies.get(tenant).version()));
    }

    private PlaceOnlineOrderCommand cmd(UUID tenant, UUID customer, UUID cid, UUID title, int qty) {
        return new PlaceOnlineOrderCommand(tenant, customer, List.of(new PlaceOnlineOrderCommand.Line(title, qty)),
                Fulfilment.PICKUP, PaymentMethod.CASH, new Recipient("Nguyen Van An", "0912345678", null), cid);
    }

    /** Kết quả một lần đặt: id đơn hoặc mã lỗi nghiệp vụ. */
    record Outcome(UUID orderId, ErrorCode error) {
        boolean ok() {
            return orderId != null;
        }
    }

    private Outcome tryPlace(UUID tenant, UUID customer, UUID cid, UUID title, int qty) {
        try {
            var r = TenantContext.callAs(tenant, () -> place.place(cmd(tenant, customer, cid, title, qty)));
            return new Outcome(r.order().id(), null);
        } catch (SalesException e) {
            return new Outcome(null, e.code());
        }
    }

    private <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

    /** Làm các đơn của tenant quá hạn giữ hàng (đặt hạn vào quá khứ) bằng quyền chủ bảng. */
    private void makeAllExpired(UUID tenant) throws SQLException {
        exec("update sales_orders set reservation_expires_at = now() - interval '1 minute' "
                + "where tenant_id = '" + tenant + "' and status = 'PENDING_CONFIRMATION'");
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

    private long stock(UUID tenant, UUID title, String column) throws SQLException {
        return scalar("select " + column + " from stock_items where tenant_id = '" + tenant + "' and title_id = '"
                + title + "'");
    }

    private long releases(UUID tenant, UUID title) throws SQLException {
        return scalar("select count(*) from stock_ledger l join stock_items s on s.id = l.stock_item_id "
                + "and s.tenant_id = l.tenant_id where l.tenant_id = '" + tenant + "' and s.title_id = '" + title
                + "' and l.type = 'RELEASE'");
    }

    private void assertReconciled(UUID tenant, UUID title) {
        var r = TenantContext.callAs(tenant, () -> reconcile.execute(tenant, title));
        assertThat(r.consistent()).as("on_hand/reserved phải bằng tổng sổ cái: " + r).isTrue();
    }

    // ===== Tồn kho =====

    @Test
    void nhieuKhachDatDongThoiKhongBanVuotTon() throws Exception {
        UUID title = stocked(tenantA, 5);

        List<Outcome> results = concurrently(20, () -> tryPlace(tenantA, UUID.randomUUID(), UUID.randomUUID(), title, 1));

        assertThat(results.stream().filter(Outcome::ok).count()).isEqualTo(5);
        assertThat(results.stream().filter(o -> !o.ok()).map(Outcome::error)).containsOnly(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(stock(tenantA, title, "reserved")).isEqualTo(5);
        assertThat(stock(tenantA, title, "on_hand")).isEqualTo(5);
        assertReconciled(tenantA, title);
    }

    // ===== Trần theo khách và idempotency =====

    @Test
    void motKhachDatDongThoiKhongVuotTranDonCho() throws Exception {
        setPolicy(tenantA, 15, 3, 50);
        UUID title = stocked(tenantA, 100);
        UUID customer = UUID.randomUUID();

        List<Outcome> results = concurrently(12, () -> tryPlace(tenantA, customer, UUID.randomUUID(), title, 1));

        assertThat(results.stream().filter(Outcome::ok).count()).isEqualTo(3);
        assertThat(results.stream().filter(o -> !o.ok()).map(Outcome::error)).containsOnly(ErrorCode.ORDER_LIMIT_EXCEEDED);
        assertThat(stock(tenantA, title, "reserved")).isEqualTo(3);
        assertThat(scalar("select count(*) from sales_orders where tenant_id = '" + tenantA + "' and customer_user_id = '"
                + customer + "'")).isEqualTo(3);
        assertReconciled(tenantA, title);
    }

    @Test
    void cungClientRequestIdDongThoiChiTaoMotDonVaGiuTonMotLan() throws Exception {
        UUID title = stocked(tenantA, 10);
        UUID customer = UUID.randomUUID();
        UUID cid = UUID.randomUUID();

        List<Outcome> results = concurrently(10, () -> tryPlace(tenantA, customer, cid, title, 2));

        assertThat(results).allMatch(Outcome::ok);
        assertThat(results.stream().map(Outcome::orderId).distinct()).hasSize(1);
        assertThat(scalar("select count(*) from sales_orders where tenant_id = '" + tenantA + "' and client_request_id = '"
                + cid + "'")).isEqualTo(1);
        assertThat(stock(tenantA, title, "reserved")).isEqualTo(2);
        assertReconciled(tenantA, title);
    }

    @Test
    void cungKhoaKhacNoiDungBiTuChoiTrenDbThat() {
        UUID title = stocked(tenantA, 10);
        UUID customer = UUID.randomUUID();
        UUID cid = UUID.randomUUID();
        assertThat(tryPlace(tenantA, customer, cid, title, 2).ok()).isTrue();

        Outcome changed = tryPlace(tenantA, customer, cid, title, 3);

        assertThat(changed.error()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(tryPlace(tenantA, customer, cid, title, 2).ok()).isTrue();
    }

    // ===== Hạn giữ hàng và worker =====

    @Test
    void donMoiCoHan15PhutVaDonCuKhongCoHanKhongBiDong() throws Exception {
        UUID title = stocked(tenantA, 10);
        Outcome o = tryPlace(tenantA, UUID.randomUUID(), UUID.randomUUID(), title, 1);

        assertThat(scalar("select count(*) from sales_orders where id = '" + o.orderId()
                + "' and reservation_expires_at is not null and request_hash is not null")).isEqualTo(1);
        assertThat(scalar("select round(extract(epoch from (reservation_expires_at - created_at)) / 60) from "
                + "sales_orders where id = '" + o.orderId() + "'")).isEqualTo(15);
    }

    @Test
    void hocWorkerChayDongThoiNhaKhoDungMotLanChoMoiDon() throws Exception {
        UUID title = stocked(tenantB, 50);
        for (int i = 0; i < 20; i++) {
            assertThat(tryPlace(tenantB, UUID.randomUUID(), UUID.randomUUID(), title, 1).ok()).isTrue();
        }
        assertThat(stock(tenantB, title, "reserved")).isEqualTo(20);
        makeAllExpired(tenantB);

        List<Integer> counts = concurrently(4, () -> TenantContext.callAs(tenantB, () -> expire.execute(tenantB)));

        assertThat(counts.stream().mapToInt(Integer::intValue).sum()).isEqualTo(20);
        assertThat(stock(tenantB, title, "reserved")).isZero();
        assertThat(stock(tenantB, title, "on_hand")).isEqualTo(50);
        assertThat(releases(tenantB, title)).isEqualTo(20);
        assertThat(scalar("select count(*) from sales_orders where tenant_id = '" + tenantB
                + "' and status = 'CANCELLED'")).isEqualTo(20);
        assertReconciled(tenantB, title);
        // Chạy lại không nhả thêm
        assertThat(TenantContext.callAs(tenantB, () -> expire.execute(tenantB))).isZero();
        assertThat(releases(tenantB, title)).isEqualTo(20);
    }

    @Test
    void xacNhanDuaVoiHetHanChiMotBenThangVaKhongLechTon() throws Exception {
        UUID title = stocked(tenantA, 100);
        for (int round = 0; round < 15; round++) {
            Outcome o = tryPlace(tenantA, UUID.randomUUID(), UUID.randomUUID(), title, 1);
            makeAllExpired(tenantA);
            long reservedBefore = stock(tenantA, title, "reserved");

            concurrently(2, () -> {
                // Hai luồng chạy song song: nhân viên xác nhận và worker hết hạn của cùng một đơn
                try {
                    return TenantContext.callAs(tenantA, () -> {
                        workflow.confirm(tenantA, staff, o.orderId(), null, null);
                        return "confirm";
                    });
                } catch (RuntimeException confirmLost) {
                    return TenantContext.callAs(tenantA, () -> String.valueOf(expiry.expireOne(tenantA, o.orderId())));
                }
            });
            TenantContext.callAs(tenantA, () -> expire.execute(tenantA));

            String status = TenantContext.callAs(tenantA, () -> orders.findById(tenantA, o.orderId()).orElseThrow())
                    .status().name();
            assertThat(status).isIn("CONFIRMED", "CANCELLED");
            long reservedAfter = stock(tenantA, title, "reserved");
            // CONFIRMED giữ nguyên hàng đã giữ; CANCELLED nhả đúng 1 cuốn. Không có kết quả lệch ở giữa.
            assertThat(reservedAfter).isEqualTo("CONFIRMED".equals(status) ? reservedBefore : reservedBefore - 1);
        }
        assertReconciled(tenantA, title);
    }

    // ===== Cách ly tenant =====

    @Test
    void workerCuaTenantNayKhongDongDonVaTonCuaTenantKhac() throws Exception {
        UUID titleA = stocked(tenantA, 10);
        UUID titleB = stocked(tenantB, 10);
        Outcome a = tryPlace(tenantA, UUID.randomUUID(), UUID.randomUUID(), titleA, 3);
        Outcome b = tryPlace(tenantB, UUID.randomUUID(), UUID.randomUUID(), titleB, 4);
        exec("update sales_orders set reservation_expires_at = now() - interval '1 minute' where id in ('"
                + a.orderId() + "','" + b.orderId() + "')");

        // Tenant A không đọc được đơn của B và worker của A chỉ nhả đơn của A
        assertThat(TenantContext.callAs(tenantA, () -> orders.findById(tenantA, b.orderId()))).isEmpty();
        assertThat(TenantContext.callAs(tenantA, () -> expiry.expireOne(tenantA, b.orderId()))).isFalse();
        assertThat(TenantContext.callAs(tenantA, () -> expire.execute(tenantA))).isGreaterThanOrEqualTo(1);

        assertThat(stock(tenantB, titleB, "reserved")).isEqualTo(4);
        assertThat(scalar("select count(*) from sales_orders where id = '" + b.orderId()
                + "' and status = 'PENDING_CONFIRMATION'")).isEqualTo(1);
        assertThat(stock(tenantA, titleA, "reserved")).isZero();
        assertThat(releases(tenantB, titleB)).isZero();

        // Hạn mức của tenant này không ảnh hưởng tenant kia
        setPolicy(tenantA, 15, 1, 50);
        UUID customer = UUID.randomUUID();
        assertThat(tryPlace(tenantA, customer, UUID.randomUUID(), titleA, 1).ok()).isTrue();
        assertThat(tryPlace(tenantA, customer, UUID.randomUUID(), titleA, 1).error())
                .isEqualTo(ErrorCode.ORDER_LIMIT_EXCEEDED);
        assertThat(tryPlace(tenantB, customer, UUID.randomUUID(), titleB, 1).ok()).isTrue();
        assertThat(tryPlace(tenantB, customer, UUID.randomUUID(), titleB, 1).ok()).isTrue();
    }
}
