package lms.sales.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lms.sales.ordering.application.OrderResponse;
import lms.sales.ordering.application.PlaceOnlineOrderCommand;
import lms.sales.ordering.application.PlaceOnlineOrderUseCase;
import lms.sales.ordering.application.PlacePosOrderCommand;
import lms.sales.ordering.application.PlacePosOrderUseCase;
import lms.sales.ordering.application.ShopOrderResponse;
import lms.sales.ordering.application.ShopOrderUseCases;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.Recipient;
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

/** Harness đo hiệu năng tạm (perf-tester), KHÔNG phải test của dự án. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/sales",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.sales.events.enabled=false",
        "lms.sales.anonymize.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
class SalesPerfIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    static final Map<UUID, CatalogLookupPort.CatalogTitle> TITLES = new ConcurrentHashMap<>();

    @Autowired PlaceOnlineOrderUseCase placeOnline;
    @Autowired PlacePosOrderUseCase placePos;
    @Autowired ShopOrderUseCases shopOrders;
    @Autowired RegisterStockItemUseCase registerItem;
    @Autowired AdjustStockUseCase adjust;
    @Autowired ReconcileStockUseCase reconcile;

    @Autowired jakarta.persistence.EntityManagerFactory emf;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean CatalogLookupPort catalog;

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

    UUID stocked(UUID tenant, long stock) {
        UUID id = UUID.randomUUID();
        TITLES.put(id, new CatalogLookupPort.CatalogTitle(id, "S", "TG", "ISBN-" + id.toString().substring(0, 8),
                BigDecimal.valueOf(100_000)));
        TenantContext.runAs(tenant, () -> {
            registerItem.execute(tenant, staff, id, null, null);
            adjust.execute(tenant, staff, id, stock, "ton dau");
        });
        return id;
    }

    long scalar(String sql) throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    long onHand(UUID t, UUID title) throws Exception {
        return scalar("select on_hand from stock_items where tenant_id='" + t + "' and title_id='" + title + "'");
    }

    long reserved(UUID t, UUID title) throws Exception {
        return scalar("select reserved from stock_items where tenant_id='" + t + "' and title_id='" + title + "'");
    }

    OrderResponse pos(UUID tenant, UUID title, int qty) {
        return TenantContext.callAs(tenant, () -> placePos.create(new PlacePosOrderCommand(tenant, staff,
                List.of(new PlacePosOrderCommand.Line(title, qty, null)), PaymentMethod.CASH, null, null, null,
                null, UUID.randomUUID())));
    }

    ShopOrderResponse online(UUID tenant, UUID customer, UUID title, int qty) {
        return TenantContext.callAs(tenant, () -> placeOnline.place(new PlaceOnlineOrderCommand(tenant, customer,
                List.of(new PlaceOnlineOrderCommand.Line(title, qty)), Fulfilment.PICKUP, PaymentMethod.CASH,
                new Recipient("Nguyen Van An", "0912345678", "1 A, Quan 1, TP HCM"), UUID.randomUUID())));
    }

    // ---------------------------------------------------------------- khung chạy
    static class Result {
        final ConcurrentLinkedQueue<Long> okNs = new ConcurrentLinkedQueue<>();
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger business = new AtomicInteger();
        final AtomicInteger unexpected = new AtomicInteger();
        final Map<String, AtomicInteger> codes = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<String> unexpectedMsgs = new ConcurrentLinkedQueue<>();
        long wallNs;
    }

    /** Mỗi luồng gọi op(threadIdx, iter) tới khi hết thời gian (durationMs>0) hoặc đủ iters. */
    Result run(int threads, int iters, long durationMs, java.util.function.BiConsumer<Integer, Integer> op)
            throws Exception {
        Result r = new Result();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        long[] end = new long[1];
        for (int t = 0; t < threads; t++) {
            final int ti = t;
            fs.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; durationMs > 0 ? System.nanoTime() < end[0] : i < iters; i++) {
                    long s = System.nanoTime();
                    try {
                        op.accept(ti, i);
                        r.okNs.add(System.nanoTime() - s);
                        r.ok.incrementAndGet();
                    } catch (SalesException e) {
                        r.business.incrementAndGet();
                        r.codes.computeIfAbsent(String.valueOf(e.code()), k -> new AtomicInteger()).incrementAndGet();
                    } catch (Throwable e) {
                        r.unexpected.incrementAndGet();
                        if (r.unexpectedMsgs.size() < 5) {
                            r.unexpectedMsgs.add(e.toString());
                        }
                    }
                }
            }));
        }
        ready.await();
        long t0 = System.nanoTime();
        end[0] = t0 + durationMs * 1_000_000L;
        go.countDown();
        for (var f : fs) {
            f.get();
        }
        r.wallNs = System.nanoTime() - t0;
        pool.shutdown();
        return r;
    }

    void report(String name, int threads, Result r) {
        long[] a = r.okNs.stream().mapToLong(Long::longValue).sorted().toArray();
        double wall = r.wallNs / 1e9;
        int total = r.ok.get() + r.business.get() + r.unexpected.get();
        String lat = a.length == 0 ? "n/a" : String.format("p50=%.1f p95=%.1f p99=%.1f max=%.1f ms",
                p(a, .5), p(a, .95), p(a, .99), a[a.length - 1] / 1e6);
        System.out.printf("PERF|%s|threads=%d|ok=%d|bizErr=%d %s|unexpected=%d|wall=%.2fs|tputAll=%.1f/s|tputOk=%.1f/s|%s%s%n",
                name, threads, r.ok.get(), r.business.get(), r.codes, r.unexpected.get(), wall, total / wall,
                r.ok.get() / wall, lat, r.unexpectedMsgs.isEmpty() ? "" : "|MSG=" + r.unexpectedMsgs);
    }

    static double p(long[] a, double q) {
        return a[Math.min(a.length - 1, (int) Math.ceil(q * a.length) - 1)] / 1e6;
    }

    void warmup() throws Exception {
        UUID t = UUID.randomUUID();
        UUID title = stocked(t, 100000);
        run(4, 150, 0, (ti, i) -> pos(t, title, 1));
    }

    @Test
    void diag() throws Exception {
        warmup();
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 300; i++) {
                s.execute("select 1");
            }
            System.out.printf("PERF|DIAG select 1 round trip avg=%.2f ms%n", (System.nanoTime() - t0) / 300 / 1e6);
            s.execute("create table dg(x int)");
            c.setAutoCommit(false);
            t0 = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                s.execute("insert into dg values (1)");
                c.commit();
            }
            System.out.printf("PERF|DIAG insert+commit avg=%.2f ms (WAL flush)%n", (System.nanoTime() - t0) / 100 / 1e6);
            c.setAutoCommit(true);
            try (ResultSet rs = s.executeQuery("show synchronous_commit")) {
                rs.next();
                System.out.println("PERF|DIAG synchronous_commit=" + rs.getString(1));
            }
            try (ResultSet rs = s.executeQuery("show fsync")) {
                rs.next();
                System.out.println("PERF|DIAG fsync=" + rs.getString(1));
            }
        }
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        UUID t = UUID.randomUUID();
        UUID title = stocked(t, 1_000_000);
        stats.clear();
        Result r = run(1, 100, 0, (ti, i) -> pos(t, title, 1));
        report("DIAG 1 luong 100 don POS", 1, r);
        System.out.printf("PERF|DIAG per order: prepareStatements=%.1f queries=%.1f entityInserts=%.1f entityUpdates=%.1f connections=%.1f%n",
                stats.getPrepareStatementCount() / 100.0, stats.getQueryExecutionCount() / 100.0,
                stats.getEntityInsertCount() / 100.0, stats.getEntityUpdateCount() / 100.0,
                stats.getConnectCount() / 100.0);
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            s.execute("alter system set synchronous_commit = off");
            s.execute("select pg_reload_conf()");
        }
        Thread.sleep(1500);
        UUID t2 = UUID.randomUUID();
        UUID title2 = stocked(t2, 1_000_000);
        report("DIAG synchronous_commit=off 1 luong", 1, run(1, 100, 0, (ti, i) -> pos(t2, title2, 1)));
        report("DIAG synchronous_commit=off 16 luong 1 tenant", 16, run(16, 0, 5000, (ti, i) -> pos(t2, title2, 1)));
    }

    // ---------------------------------------------------------------- P1.1
    @Test
    void p11_pos() throws Exception {
        warmup();
        UUID t0 = UUID.randomUUID();
        UUID h0 = stocked(t0, 100000);
        Result base = run(1, 300, 0, (ti, i) -> pos(t0, h0, 1));
        report("P1.1 baseline 1 luong 1 sach", 1, base);

        UUID t1 = UUID.randomUUID();
        UUID hot = stocked(t1, 1_000_000);
        long before = onHand(t1, hot);
        Result r1 = run(50, 40, 0, (ti, i) -> pos(t1, hot, 1 + (i % 3)));
        report("P1.1 hot 50 luong 1 sach ton du (2000 don qty 1-3)", 50, r1);
        long sold = scalar("select coalesce(sum(l.quantity),0) from sales_order_lines l join sales_orders o on "
                + "o.id=l.order_id where o.tenant_id='" + t1 + "' and l.title_id='" + hot + "'");
        long after = onHand(t1, hot);
        System.out.printf("PERF|P1.1 hot check|onHand before=%d after=%d sold=%d => %s%n", before, after, sold,
                after == before - sold && after >= 0 ? "OK" : "SAI");
        System.out.println("PERF|P1.1 hot reconcile consistent="
                + TenantContext.callAs(t1, () -> reconcile.execute(t1, hot)).consistent());

        UUID t2 = UUID.randomUUID();
        UUID low = stocked(t2, 300);
        Result r2 = run(50, 10, 0, (ti, i) -> pos(t2, low, 1));
        report("P1.1 hot 50 luong ton=300 (500 lan qty1)", 50, r2);
        System.out.printf("PERF|P1.1 low check|onHand after=%d (ky vong 0) ok=%d (ky vong 300) bizErr=%s reconcile=%s%n",
                onHand(t2, low), r2.ok.get(), r2.codes, TenantContext.callAs(t2, () -> reconcile.execute(t2, low))
                        .consistent());

        UUID t3 = UUID.randomUUID();
        List<UUID> titles = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            titles.add(stocked(t3, 1_000_000));
        }
        Result r3 = run(50, 40, 0, (ti, i) -> pos(t3, titles.get((ti * 40 + i) % 200), 1));
        report("P1.1 many 50 luong 200 sach (1 tenant 2000 don)", 50, r3);
        long tot = scalar("select sum(1000000 - on_hand) from stock_items where tenant_id='" + t3 + "'");
        System.out.printf("PERF|P1.1 many check|sum(sold)=%d expected=%d%n", tot, r3.ok.get());
    }

    // ---------------------------------------------------------------- điểm nóng sales_counters
    @Test
    void counter_scaling() throws Exception {
        warmup();
        int[] ths = {1, 2, 4, 8, 16, 32, 50};
        UUID t = UUID.randomUUID();
        List<UUID> titles = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            titles.add(stocked(t, 10_000_000));
        }
        for (int n : ths) {
            Result r = run(n, 0, 6000, (ti, i) -> pos(t, titles.get((ti * 7 + i) % 100), 1));
            report("COUNTER 1 tenant titles khac nhau", n, r);
        }
        int[][] cfg = {{2, 1}, {4, 1}, {8, 1}, {4, 2}, {8, 4}, {16, 1}};
        for (int[] c : cfg) {
            int k = c[0];
            int per = c[1];
            List<UUID> tenants = new ArrayList<>();
            List<List<UUID>> tt = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                UUID x = UUID.randomUUID();
                tenants.add(x);
                List<UUID> l = new ArrayList<>();
                for (int j = 0; j < 20; j++) {
                    l.add(stocked(x, 10_000_000));
                }
                tt.add(l);
            }
            Result r = run(k * per, 0, 6000, (ti, i) -> pos(tenants.get(ti % k), tt.get(ti % k).get(i % 20), 1));
            report("COUNTER " + k + " tenant x " + per + " luong", k * per, r);
        }
    }

    // ---------------------------------------------------------------- P2.1
    @Test
    void p21_online() throws Exception {
        warmup();
        UUID tenant = UUID.randomUUID();
        UUID title = stocked(tenant, 400);
        Result a = run(50, 10, 0, (ti, i) -> online(tenant, UUID.randomUUID(), title, 1));
        report("P2.1 A dat online 50 luong ton=400 (500 lan)", 50, a);
        System.out.printf("PERF|P2.1 A check|reserved=%d expected(ok)=%d%n", reserved(tenant, title), a.ok.get());

        UUID title2 = stocked(tenant, 1000);
        ConcurrentLinkedQueue<UUID[]> placed2 = new ConcurrentLinkedQueue<>();
        Result b = run(50, 0, 6000, (ti, i) -> {
            if (ti % 2 == 0) {
                UUID cust = UUID.randomUUID();
                var o = online(tenant, cust, title2, 1 + (i % 2));
                placed2.add(new UUID[]{o.order().id(), cust});
            } else {
                UUID[] x = placed2.poll();
                if (x == null) {
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new SalesException(ErrorCode.ORDER_NOT_FOUND, "no-op");
                }
                TenantContext.runAs(tenant, () -> shopOrders.cancelMyOrder(tenant, x[1], x[0], "huy"));
            }
        });
        report("P2.1 B dat+huy dong thoi 25+25 luong 6s (bizErr ORDER_NOT_FOUND = huy khi hang doi rong, no-op)", 50, b);
        long expected = scalar("select coalesce(sum(l.quantity),0) from sales_order_lines l join sales_orders o on "
                + "o.id=l.order_id where o.tenant_id='" + tenant + "' and l.title_id='" + title2
                + "' and o.status <> 'CANCELLED'");
        long cancelled = scalar("select count(*) from sales_orders o join sales_order_lines l on l.order_id=o.id "
                + "where o.tenant_id='" + tenant + "' and l.title_id='" + title2 + "' and o.status='CANCELLED'");
        long res = reserved(tenant, title2);
        System.out.printf("PERF|P2.1 B check|reserved=%d tongDonHieuLuc=%d onHand=%d soDonHuy=%d => %s reconcile=%s%n",
                res, expected, onHand(tenant, title2), cancelled, res == expected ? "OK" : "SAI",
                TenantContext.callAs(tenant, () -> reconcile.execute(tenant, title2)).consistent());

        UUID cust = UUID.randomUUID();
        UUID title3 = stocked(tenant, 10);
        var one = online(tenant, cust, title3, 5);
        Result c = run(20, 1, 0, (ti, i) -> TenantContext.runAs(tenant,
                () -> shopOrders.cancelMyOrder(tenant, cust, one.order().id(), "x")));
        report("P2.1 C 20 luong cung huy 1 don", 20, c);
        System.out.printf("PERF|P2.1 C check|reserved=%d (ky vong 0) onHand=%d%n", reserved(tenant, title3),
                onHand(tenant, title3));
    }
}
