package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.RegisterStockItemUseCase;
import lms.sales.stock.application.StockPort;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.hibernate.SessionFactory;
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
 * Đồng bộ giá bìa (storefront sắp theo giá) ghi theo lô: số câu SQL không tăng theo số mặt hàng; đồng thời kiểm các
 * index của migration V11 đã được tạo (Flyway chạy toàn bộ migration sales trong container).
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
class CoverPriceRefreshIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired StockPort stock;
    @Autowired RegisterStockItemUseCase registerItem;
    @Autowired jakarta.persistence.EntityManagerFactory emf;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean CatalogLookupPort catalog;

    @Test
    void refreshCoverPricesGhiTheoLoVaDungGiaTri() throws Exception {
        when(catalog.lookup(any())).thenAnswer(inv -> {
            Map<UUID, CatalogLookupPort.CatalogTitle> out = new HashMap<>();
            for (UUID id : (java.util.Collection<UUID>) inv.getArgument(0)) {
                out.put(id, new CatalogLookupPort.CatalogTitle(id, "S", "TG", "ISBN-" + id.toString().substring(0, 8),
                        null, null, false, null, null, null, null, null, null, null, null, null, null, null));
            }
            return out;
        });
        UUID tenant = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        Map<UUID, Long> prices = new LinkedHashMap<>();
        for (int i = 0; i < 120; i++) { // 2 lô (100 + 20)
            UUID title = UUID.randomUUID();
            TenantContext.runAs(tenant, () -> registerItem.execute(tenant, staff, title, null, null, null));
            prices.put(title, 10_000L + prices.size() * 1_000L);
        }
        UUID untouched = UUID.randomUUID();
        TenantContext.runAs(tenant, () -> registerItem.execute(tenant, staff, untouched, null, null, null));

        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        long before = stats.getPrepareStatementCount();
        TenantContext.runAs(tenant, () -> stock.refreshCoverPrices(tenant, prices));
        long statements = stats.getPrepareStatementCount() - before;

        assertThat(statements).as("câu SQL cho 120 mặt hàng").isLessThanOrEqualTo(4);
        for (Map.Entry<UUID, Long> e : prices.entrySet()) {
            assertThat(snapshot(tenant, e.getKey())).isEqualTo(e.getValue());
        }
        assertThat(snapshot(tenant, untouched)).isNull();

        // giá trị âm bị bỏ qua, giá trị rỗng không làm hỏng lô
        Map<UUID, Long> bad = new HashMap<>();
        bad.put(untouched, -5L);
        TenantContext.runAs(tenant, () -> stock.refreshCoverPrices(tenant, bad));
        assertThat(snapshot(tenant, untouched)).isNull();
    }

    @Test
    void indexMigrationV11DaTao() throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from pg_indexes where indexname in "
                     + "('idx_goods_receipts_tenant_status_created','idx_goods_receipts_tenant_supplier_created',"
                     + "'idx_sales_orders_pii_pending_cancelled')")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(3);
        }
    }

    private Long snapshot(UUID tenant, UUID title) throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select cover_price_snapshot from stock_items where tenant_id='" + tenant
                     + "' and title_id='" + title + "'")) {
            rs.next();
            long v = rs.getLong(1);
            return rs.wasNull() ? null : v;
        }
    }
}
