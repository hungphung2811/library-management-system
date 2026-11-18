package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.CatalogLookupPort.CatalogTitle;
import lms.sales.stock.application.RegisterStockItemUseCase;
import lms.sales.stock.application.StockPort;
import lms.sales.stock.application.StorefrontQueryUseCases;
import lms.sales.stock.application.StorefrontQueryUseCases.ItemDetailView;
import lms.sales.stock.application.StorefrontQueryUseCases.ItemView;
import lms.sales.stock.application.StorefrontSort;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0026 mục 3.6 trên PostgreSQL thật, chạy bằng vai trò sales_app (RLS): gộp theo tác phẩm, biến thể, snapshot
 * work_id, backfill V14 và số câu SQL không tăng theo số dòng.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/sales",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.sales.events.enabled=false",
        "lms.sales.anonymize.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
class StorefrontWorkGroupingIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired StockPort stock;
    @Autowired RegisterStockItemUseCase registerItem;
    @Autowired StorefrontQueryUseCases storefront;
    @Autowired jakarta.persistence.EntityManagerFactory emf;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean CatalogLookupPort catalog;

    private final Map<UUID, CatalogTitle> titles = new HashMap<>();

    private void stubCatalog() {
        when(catalog.lookup(any())).thenAnswer(inv -> {
            Map<UUID, CatalogTitle> out = new HashMap<>();
            for (UUID id : (Collection<UUID>) inv.getArgument(0)) {
                if (titles.containsKey(id)) {
                    out.put(id, titles.get(id));
                }
            }
            return out;
        });
    }

    private UUID edition(UUID tenant, String name, long cover, UUID workId, Integer part) {
        UUID id = UUID.randomUUID();
        titles.put(id, new CatalogTitle(id, name, "TG", "ISBN-" + id.toString().substring(0, 8),
                BigDecimal.valueOf(cover), null, false, null, 2020, null, "vi", null, "PAPERBACK", null, null, null,
                null, List.of(), workId, "Tác phẩm " + workId, part, part == null ? null : "Tập " + part));
        TenantContext.runAs(tenant, () -> registerItem.execute(tenant, UUID.randomUUID(), id, null, null, null));
        return id;
    }

    private long statements(Runnable r) {
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        long before = stats.getPrepareStatementCount();
        r.run();
        return stats.getPrepareStatementCount() - before;
    }

    @Test
    void gopTheoTacPhamBienTheVaSoCauSqlKhongTangTheoSoDong() {
        stubCatalog();
        UUID tenant = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID wFirst = UUID.randomUUID();
        for (int w = 0; w < 10; w++) {
            UUID work = w == 0 ? wFirst : UUID.randomUUID();
            edition(tenant, "W" + w + "-a", 10_000 + w, work, 1);
            edition(tenant, "W" + w + "-b", 20_000 + w, work, 2);
            edition(tenant, "W" + w + "-c", 30_000 + w, work, null);
        }
        UUID alien = edition(other, "Của tenant khác", 5_000, UUID.randomUUID(), null);

        List<StockPort.WorkGroup>[] small = new List[1];
        List<StockPort.WorkGroup>[] large = new List[1];
        long forFew = statements(() -> TenantContext.runAs(tenant,
                () -> small[0] = stock.listStorefrontByWork(tenant, StorefrontSort.NEWEST, false, 0, 3).items()));
        long forMany = statements(() -> TenantContext.runAs(tenant,
                () -> large[0] = stock.listStorefrontByWork(tenant, StorefrontSort.PRICE_ASC, false, 0, 10).items()));

        assertThat(small[0]).hasSize(3);
        assertThat(large[0]).hasSize(10);
        assertThat(large[0]).allSatisfy(g -> assertThat(g.variantCount()).isEqualTo(3));
        assertThat(large[0].get(0).priceFrom()).isNotNull();
        assertThat(forFew).as("câu SQL cho 3 nhóm").isLessThanOrEqualTo(3);
        assertThat(forMany).as("câu SQL cho 10 nhóm").isEqualTo(forFew);
        // RLS: không thấy tenant khác
        assertThat(large[0]).extracting(g -> g.representative().titleId()).doesNotContain(alien);
        assertThat(TenantContext.callAs(tenant, () -> stock.listStorefrontByWork(tenant, StorefrontSort.NEWEST,
                false, 0, 50).total())).isEqualTo(10);

        // use case: danh sách WORK gộp, chi tiết có variants
        PageHolder page = new PageHolder();
        TenantContext.runAs(tenant, () -> page.items = storefront.list(tenant, null, "price_asc", null, "WORK", 0, 20)
                .items());
        assertThat(page.items).hasSize(10);
        assertThat(page.items).allSatisfy(i -> {
            assertThat(i.variantCount()).isEqualTo(3);
            assertThat(i.priceFrom()).isNotNull();
        });
        ItemView first = page.items.get(0);
        ItemDetailHolder detail = new ItemDetailHolder();
        TenantContext.runAs(tenant, () -> detail.view = storefront.get(tenant, first.titleId()));
        assertThat(detail.view.workId()).isEqualTo(first.workId());
        assertThat(detail.view.variants()).hasSize(3);
        assertThat(detail.view.variants().get(0).partNumber()).isEqualTo(1);
        assertThat(detail.view.variants().get(2).partNumber()).isNull();
    }

    @Test
    void refreshWorkIdsGhiTheoLoVaTuSuaKhiXemChiTiet() throws Exception {
        stubCatalog();
        UUID tenant = UUID.randomUUID();
        UUID w1 = UUID.randomUUID();
        UUID id = edition(tenant, "X", 10_000, w1, null);
        assertThat(workId(tenant, id)).isEqualTo(w1);

        UUID w2 = UUID.randomUUID();
        CatalogTitle cur = titles.get(id);
        titles.put(id, new CatalogTitle(id, cur.name(), cur.author(), cur.isbn(), cur.coverPrice(), null, false, null,
                null, null, null, null, null, null, null, null, null, List.of(), w2, "Mới", null, null));

        TenantContext.runAs(tenant, () -> storefront.get(tenant, id));

        assertThat(workId(tenant, id)).isEqualTo(w2);
    }

    @Test
    void backfillV14TinhDungMd5ChoDongCuTheoTenantBangVaiTroApp() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID titleA = UUID.randomUUID();
        UUID titleB = UUID.randomUUID();
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            for (Object[] row : new Object[][]{{tenantA, titleA}, {tenantB, titleB}}) {
                s.execute("insert into stock_items (id, tenant_id, title_id) values ('" + UUID.randomUUID() + "','"
                        + row[0] + "','" + row[1] + "')");
            }
        }
        String sql = new String(new ClassPathResource("db/migration/sales/V14__stock_items_work_id.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int from = sql.indexOf("SELECT set_config('app.tenant_scope'");
        int to = sql.indexOf("-- Gom nhóm");
        assertThat(from).isPositive();
        assertThat(to).isGreaterThan(from);
        try (Connection c = AppRoleDatabase.appRole(postgres)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute(sql.substring(from, to));
            }
            c.commit();
        }
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select title_id, work_id, md5('lms-work:' || title_id::text)::uuid "
                     + "from stock_items where title_id in ('" + titleA + "','" + titleB + "')")) {
            int n = 0;
            while (rs.next()) {
                n++;
                assertThat(rs.getObject(2)).isEqualTo(rs.getObject(3));
            }
            assertThat(n).isEqualTo(2);
        }
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from pg_indexes where indexname = 'idx_stock_items_work'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    private UUID workId(UUID tenant, UUID title) throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select work_id from stock_items where tenant_id='" + tenant
                     + "' and title_id='" + title + "'")) {
            rs.next();
            return (UUID) rs.getObject(1);
        }
    }

    private static final class PageHolder {
        List<ItemView> items = new ArrayList<>();
    }

    private static final class ItemDetailHolder {
        ItemDetailView view;
    }
}
