package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lms.imports.core.ChunkResult;
import lms.imports.core.ImportContext;
import lms.imports.core.ImportResult;
import lms.imports.core.ImportRow;
import lms.imports.core.ImportSchema;
import lms.imports.core.OutcomeStatus;
import lms.imports.core.RowStatus;
import lms.imports.engine.RowChecks;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.StockPort;
import lms.sales.stock.application.SupplierRepository;
import lms.sales.stock.application.imports.StockItemImportHandler;
import lms.sales.stock.application.imports.SupplierImportHandler;
import lms.sales.stock.domain.StockItem;
import lms.sales.stock.domain.Supplier;
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
 * ADR-0024 pha 2 trên PostgreSQL thật (RLS bằng vai trò sales_app): validate/commit hai loại nhập, số câu SQL của một
 * chunk 200 dòng không tăng theo số dòng (JDBC batch), sổ cái khớp bộ đếm khi có tồn đầu kỳ, vi phạm unique được dịch
 * thành mã nghiệp vụ, audit tóm tắt ghi đúng một dòng.
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
class SalesImportIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired StockItemImportHandler stockHandler;
    @Autowired SupplierImportHandler supplierHandler;
    @Autowired StockPort stock;
    @Autowired SupplierRepository suppliers;
    @Autowired jakarta.persistence.EntityManagerFactory emf;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean CatalogLookupPort catalog;

    private ImportContext ctx(UUID tenant) {
        return new ImportContext(tenant, UUID.randomUUID().toString(), UUID.randomUUID(), Set.of("SALES"), false);
    }

    private ImportRow checked(ImportSchema schema, int n, Map<String, String> values) {
        return RowChecks.check(schema.sheets().get(0), ImportRow.of(schema.sheets().get(0).name(), n, values));
    }

    private long statements(Runnable body) {
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        long before = stats.getPrepareStatementCount();
        body.run();
        return stats.getPrepareStatementCount() - before;
    }

    private long count(String sql) throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void chunk200MatHangKhongOnHandTonTaoItCauSqlKhongTangTheoSoDong() throws Exception {
        UUID tenant = UUID.randomUUID();
        Map<String, CatalogLookupPort.CatalogTitle> titles = new LinkedHashMap<>();
        List<ImportRow> rows = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String isbn = "ISBN-A-" + i;
            titles.put(isbn, new CatalogLookupPort.CatalogTitle(UUID.randomUUID(), "S" + i, "TG", isbn, BigDecimal.valueOf(10_000 + i)));
            rows.add(checked(stockHandler.schema(), i + 2, Map.of("isbn", isbn, "sellingPrice", "20000", "sku", "SKU-A-" + i)));
        }
        when(catalog.lookupByIsbn(any())).thenAnswer(inv -> {
            Map<String, CatalogLookupPort.CatalogTitle> out = new HashMap<>();
            for (String i : (Collection<String>) inv.getArgument(0)) {
                out.put(i, titles.get(i));
            }
            return out;
        });
        ImportContext ctx = ctx(tenant);
        AtomicReference<List<ImportRow>> validated = new AtomicReference<>();

        long validateStatements = statements(() -> TenantContext.runAs(tenant,
                () -> validated.set(stockHandler.validate(ctx, rows))));
        assertThat(validated.get()).allMatch(r -> r.status() == RowStatus.VALID);
        assertThat(validateStatements).as("validate 200 dòng: 2 truy vấn kiểm trùng").isLessThanOrEqualTo(3);

        AtomicReference<ChunkResult> result = new AtomicReference<>();
        long commitStatements = statements(() -> TenantContext.runAs(tenant,
                () -> result.set(stockHandler.commitChunk(ctx, validated.get()))));

        assertThat(result.get().outcomes()).hasSize(200).allMatch(o -> o.status() == OutcomeStatus.CREATED);
        assertThat(commitStatements).as("commit chunk 200 dòng (kiểm trùng, chèn batch, giá bìa)").isLessThanOrEqualTo(12);
        assertThat(count("select count(*) from stock_items where tenant_id = '" + tenant + "'")).isEqualTo(200);
        assertThat(count("select count(*) from stock_items where tenant_id = '" + tenant
                + "' and cover_price_snapshot is not null")).isEqualTo(200);
        assertThat(count("select count(*) from stock_ledger sl join stock_items si on si.id = sl.stock_item_id where si.tenant_id = '"
                + tenant + "'")).isZero();

        // chạy lại cùng chunk (confirm lặp/tiếp tục sau ngắt): mọi dòng thành SKIPPED_DUPLICATE, không ghi thêm
        AtomicReference<ChunkResult> again = new AtomicReference<>();
        TenantContext.runAs(tenant, () -> again.set(stockHandler.commitChunk(ctx, validated.get())));
        assertThat(again.get().outcomes()).allMatch(o -> o.status() == OutcomeStatus.SKIPPED_DUPLICATE);
        assertThat(count("select count(*) from stock_items where tenant_id = '" + tenant + "'")).isEqualTo(200);
    }

    @Test
    void tonDauKyGhiQuaSoCaiADJUSTMENTVaKhopBoDem() throws Exception {
        UUID tenant = UUID.randomUUID();
        Map<String, CatalogLookupPort.CatalogTitle> titles = new LinkedHashMap<>();
        List<ImportRow> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String isbn = "ISBN-B-" + i;
            titles.put(isbn, new CatalogLookupPort.CatalogTitle(UUID.randomUUID(), "S", "TG", isbn, BigDecimal.TEN));
            rows.add(checked(stockHandler.schema(), i + 2, Map.of("isbn", isbn, "openingQuantity", String.valueOf(i + 1))));
        }
        when(catalog.lookupByIsbn(any())).thenAnswer(inv -> {
            Map<String, CatalogLookupPort.CatalogTitle> out = new HashMap<>();
            for (String i : (Collection<String>) inv.getArgument(0)) {
                out.put(i, titles.get(i));
            }
            return out;
        });
        ImportContext ctx = ctx(tenant);
        AtomicReference<List<ImportRow>> validated = new AtomicReference<>();
        TenantContext.runAs(tenant, () -> validated.set(stockHandler.validate(ctx, rows)));
        TenantContext.runAs(tenant, () -> stockHandler.commitChunk(ctx, validated.get()));

        assertThat(count("select count(*) from stock_ledger sl join stock_items si on si.id = sl.stock_item_id "
                + "where si.tenant_id = '" + tenant + "' and sl.type = 'ADJUSTMENT'")).isEqualTo(30);
        // mỗi mặt hàng: on_hand = tổng delta sổ cái
        assertThat(count("select count(*) from stock_items si where si.tenant_id = '" + tenant + "' and si.on_hand <> "
                + "(select coalesce(sum(sl.delta_on_hand), 0) from stock_ledger sl where sl.stock_item_id = si.id)"))
                .isZero();
        assertThat(count("select sum(on_hand) from stock_items where tenant_id = '" + tenant + "'")).isEqualTo(30 * 31 / 2);

        // audit tóm tắt: đúng một dòng cho lần confirm
        TenantContext.runAs(tenant, () -> stockHandler.onCompleted(ctx, new ImportResult(ctx.importId(), "stock-items", 30, 0, 0, true)));
        assertThat(count("select count(*) from sales_audit_log where tenant_id = '" + tenant
                + "' and action = 'STOCK_ITEMS_IMPORTED'")).isEqualTo(1);
    }

    @Test
    void viPhamUniqueDichThanhMaNghiepVu() {
        UUID tenant = UUID.randomUUID();
        UUID title = UUID.randomUUID();
        TenantContext.runAs(tenant, () -> stock.insertAll(List.of(StockItem.register(tenant, title, null, null,
                lms.sales.stock.domain.StockItemDetails.of("DUP-SKU", null, null, null, null, null, null)))));
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> stock.insertAll(List.of(
                StockItem.register(tenant, UUID.randomUUID(), null, null,
                        lms.sales.stock.domain.StockItemDetails.of("DUP-SKU", null, null, null, null, null, null))))))
                .isInstanceOfSatisfying(SalesException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SKU_EXISTS));
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> stock.insertAll(List.of(
                StockItem.register(tenant, title, null, null)))))
                .isInstanceOfSatisfying(SalesException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.STOCK_ITEM_EXISTS));
    }

    @Test
    void nhaCungCap200DongGhiTheoLoVaBoQuaTenCoSan() throws Exception {
        UUID tenant = UUID.randomUUID();
        TenantContext.runAs(tenant, () -> suppliers.save(Supplier.create(tenant, "Có Sẵn", null, null, null, null)));
        List<ImportRow> rows = new ArrayList<>();
        for (int i = 0; i < 199; i++) {
            rows.add(checked(supplierHandler.schema(), i + 2, Map.of("name", "NCC " + i, "phone", "09" + i)));
        }
        rows.add(checked(supplierHandler.schema(), 201, Map.of("name", "có sẵn")));
        ImportContext ctx = ctx(tenant);
        AtomicReference<List<ImportRow>> validated = new AtomicReference<>();
        long validateStatements = statements(() -> TenantContext.runAs(tenant, () -> validated.set(supplierHandler.validate(ctx, rows))));
        assertThat(validateStatements).isLessThanOrEqualTo(2);
        assertThat(validated.get().get(199).status()).isEqualTo(RowStatus.DUPLICATE);

        List<ImportRow> valid = validated.get().stream().filter(r -> r.status() == RowStatus.VALID).toList();
        AtomicReference<ChunkResult> result = new AtomicReference<>();
        long commitStatements = statements(() -> TenantContext.runAs(tenant, () -> result.set(supplierHandler.commitChunk(ctx, valid))));
        assertThat(result.get().outcomes()).hasSize(199).allMatch(o -> o.status() == OutcomeStatus.CREATED);
        assertThat(commitStatements).as("commit 199 nhà cung cấp: 1 kiểm trùng + chèn batch").isLessThanOrEqualTo(6);
        assertThat(count("select count(*) from suppliers where tenant_id = '" + tenant + "'")).isEqualTo(200);
        assertThat(count("select count(*) from suppliers where tenant_id = '" + tenant + "' and active")).isEqualTo(200);

        // trùng tên đồng thời: chunk chứa dòng đã có (qua kiểm lại) bị bỏ qua, các dòng khác vẫn ghi
        List<ImportRow> mixed = List.of(checked(supplierHandler.schema(), 2, Map.of("name", "NCC 0")),
                checked(supplierHandler.schema(), 3, Map.of("name", "NCC Mới")));
        AtomicReference<ChunkResult> r2 = new AtomicReference<>();
        TenantContext.runAs(tenant, () -> r2.set(supplierHandler.commitChunk(ctx, mixed)));
        assertThat(r2.get().outcomes()).extracting(o -> o.status())
                .containsExactly(OutcomeStatus.SKIPPED_DUPLICATE, OutcomeStatus.CREATED);

        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> suppliers.insertAll(List.of(
                Supplier.create(tenant, "ncc 1", null, null, null, null)))))
                .isInstanceOfSatisfying(SalesException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SUPPLIER_EXISTS));

        TenantContext.runAs(tenant, () -> supplierHandler.onCompleted(ctx, new ImportResult(ctx.importId(), "suppliers", 200, 1, 0, true)));
        assertThat(count("select count(*) from sales_audit_log where tenant_id = '" + tenant
                + "' and action = 'SUPPLIERS_IMPORTED'")).isEqualTo(1);
    }
}
