package lms.sales.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lms.imports.core.ChunkResult;
import lms.imports.core.ImportContext;
import lms.imports.core.ImportResult;
import lms.imports.core.ImportRow;
import lms.imports.core.IssueCodes;
import lms.imports.core.OutcomeStatus;
import lms.imports.core.RowStatus;
import lms.imports.engine.RowChecks;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.StockPort;
import lms.sales.stock.application.imports.StockItemImportExecutor;
import lms.sales.stock.application.imports.StockItemImportHandler;
import lms.sales.stock.application.imports.SupplierImportExecutor;
import lms.sales.stock.application.imports.SupplierImportHandler;
import lms.sales.stock.domain.MovementType;
import lms.sales.stock.domain.StockItem;
import lms.sales.stock.domain.StockMovement;
import lms.sales.stock.domain.Supplier;
import lms.sales.support.Harness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ADR-0024: validate/commit của hai loại nhập sales (mặt hàng kèm tồn đầu kỳ, nhà cung cấp) trên bộ giả trong bộ nhớ. */
class ImportHandlersTest {

    /** Catalog giả: ISBN -> đầu sách, đếm số lần gọi lookupByIsbn (phải là MỘT lần cho cả file). */
    static final class IsbnCatalog implements CatalogLookupPort {
        final Map<String, CatalogTitle> byIsbn = new HashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        final List<Integer> sizes = new ArrayList<>();
        boolean down;

        UUID add(String isbn, long cover) {
            UUID id = UUID.randomUUID();
            byIsbn.put(isbn, new CatalogTitle(id, "Sách " + isbn, "TG", isbn, BigDecimal.valueOf(cover)));
            return id;
        }

        @Override
        public Map<UUID, CatalogTitle> lookup(Collection<UUID> titleIds) {
            return Map.of();
        }

        @Override
        public Map<String, CatalogTitle> lookupByIsbn(Collection<String> isbns) {
            calls.incrementAndGet();
            sizes.add(isbns.size());
            if (down) {
                throw new SalesException(ErrorCode.CATALOG_UNAVAILABLE, "Không gọi được catalog-service");
            }
            Map<String, CatalogTitle> out = new HashMap<>();
            isbns.forEach(i -> {
                if (byIsbn.containsKey(i)) {
                    out.put(i, byIsbn.get(i));
                }
            });
            return out;
        }
    }

    Harness h;
    IsbnCatalog catalog;
    StockItemImportHandler stockHandler;
    SupplierImportHandler supplierHandler;
    AtomicInteger cacheEvictions;
    ImportContext ctx;

    @BeforeEach
    void setUp() {
        h = new Harness();
        catalog = new IsbnCatalog();
        stockHandler = stockHandler(h.stockPort);
        cacheEvictions = new AtomicInteger();
        supplierHandler = new SupplierImportHandler(h.supplierRepo,
                new SupplierImportExecutor(h.supplierRepo, h.auditPort, h.clock), cacheEvictions::incrementAndGet);
        ctx = new ImportContext(h.tenant, h.user.toString(), UUID.randomUUID(), Set.of("SALES"), false);
    }

    private StockItemImportHandler stockHandler(StockPort port) {
        return new StockItemImportHandler(catalog, port, new StockItemImportExecutor(port,
                new lms.sales.stock.application.StockService(port, h.clock), h.auditPort, h.clock));
    }

    private ImportRow stockRow(int n, String... kv) {
        return row(StockItemImportHandler.SHEET, StockItemImportHandler.TYPE, n, kv);
    }

    private ImportRow supplierRow(int n, String... kv) {
        return row(SupplierImportHandler.SHEET, SupplierImportHandler.TYPE, n, kv);
    }

    /** Dòng đã qua kiểm cú pháp chung như luồng upload thật. */
    private ImportRow row(String sheet, String type, int n, String... kv) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            values.put(kv[i], kv[i + 1]);
        }
        var schema = type.equals(StockItemImportHandler.TYPE) ? stockHandler.schema() : supplierHandler.schema();
        return RowChecks.check(schema.sheet(sheet), ImportRow.of(sheet, n, values));
    }

    // ------------------------------------------------------------------ mặt hàng: validate

    @Test
    void validateTraIsbnMotLanVaPhanLoaiTungDong() {
        UUID ok = catalog.add("111", 90_000);
        UUID stocked = catalog.add("222", 50_000);
        catalog.add("333", 50_000);
        catalog.add("444", 50_000);
        catalog.add("555", 50_000);
        // đầu sách "222" đã là mặt hàng (Harness cần catalog cũ để đăng ký)
        h.catalogTitles.put(stocked, new CatalogLookupPort.CatalogTitle(stocked, "x", "y", "222", BigDecimal.TEN));
        h.registerItem.execute(h.tenant, h.user, stocked, null, null);
        // mặt hàng khác đang giữ SKU "A-1"
        UUID holder = UUID.randomUUID();
        h.catalogTitles.put(holder, new CatalogLookupPort.CatalogTitle(holder, "h", "y", "999", BigDecimal.TEN));
        h.registerItem.execute(h.tenant, h.user, holder, null, null, lms.sales.stock.domain.StockItemDetails.of("A-1",
                null, null, null, null, null, null));

        List<ImportRow> rows = List.of(
                stockRow(2, "isbn", "111", "sellingPrice", "85000", "sku", "b-1", "openingQuantity", "10"), // hợp lệ
                stockRow(3, "isbn", "000"), // ISBN không có trong catalog
                stockRow(4, "isbn", "222"), // đã là mặt hàng
                stockRow(5, "isbn", "333", "sku", "a-1"), // SKU đã thuộc mặt hàng khác
                stockRow(6, "isbn", "444", "sellingPrice", "-5"), // giá âm
                stockRow(7, "isbn", "111"), // trùng ISBN trong file
                stockRow(8, "isbn", "555", "sku", "B-1"), // trùng SKU trong file (không phân biệt hoa thường)
                stockRow(9, "isbn", "", "sellingPrice", "1"), // thiếu ISBN: lỗi cú pháp chung
                stockRow(10, "isbn", "444", "sellingPrice", "1000.5")); // giá lẻ

        List<ImportRow> out = stockHandler.validate(ctx, rows);

        assertThat(catalog.calls.get()).as("MỘT lời gọi catalog theo lô cho cả file").isEqualTo(1);
        assertThat(out).hasSameSizeAs(rows);
        assertThat(out.get(0).status()).isEqualTo(RowStatus.VALID);
        assertThat(out.get(0).extra()).containsEntry("titleId", ok.toString()).containsEntry("coverPrice", "90000");
        assertThat(out.get(1).status()).isEqualTo(RowStatus.INVALID);
        assertThat(out.get(1).issues()).anyMatch(i -> i.code().equals("TITLE_NOT_FOUND") && "isbn".equals(i.column()));
        assertThat(out.get(2).status()).isEqualTo(RowStatus.DUPLICATE);
        assertThat(out.get(2).issues()).anyMatch(i -> i.code().equals("STOCK_ITEM_EXISTS"));
        assertThat(out.get(3).status()).isEqualTo(RowStatus.DUPLICATE);
        assertThat(out.get(3).issues()).anyMatch(i -> i.code().equals("SKU_EXISTS"));
        assertThat(out.get(4).status()).isEqualTo(RowStatus.INVALID);
        assertThat(out.get(4).issues()).anyMatch(i -> "sellingPrice".equals(i.column()));
        assertThat(out.get(5).status()).isEqualTo(RowStatus.INVALID);
        assertThat(out.get(5).issues()).anyMatch(i -> i.code().equals(IssueCodes.DUPLICATE_IN_FILE) && "isbn".equals(i.column()));
        assertThat(out.get(6).status()).isEqualTo(RowStatus.INVALID);
        assertThat(out.get(6).issues()).anyMatch(i -> i.code().equals(IssueCodes.DUPLICATE_IN_FILE) && "sku".equals(i.column()));
        assertThat(out.get(7).status()).isEqualTo(RowStatus.INVALID);
        assertThat(out.get(8).status()).isEqualTo(RowStatus.INVALID);
    }

    @Test
    void validateChiGoiCatalogVoiIsbnCuaDongConHopLe() {
        catalog.add("111", 1);
        stockHandler.validate(ctx, List.of(stockRow(2, "isbn", "111"), stockRow(3, "isbn", "999", "sellingPrice", "x")));
        assertThat(catalog.sizes).containsExactly(1);
        // không có dòng hợp lệ thì không gọi catalog
        catalog.calls.set(0);
        stockHandler.validate(ctx, List.of(stockRow(2, "isbn", "")));
        assertThat(catalog.calls.get()).isZero();
    }

    @Test
    void catalogKhongKhaDungThiValidateNemLoiRoKhongTreo() {
        catalog.add("111", 1);
        catalog.down = true;
        assertThatThrownBy(() -> stockHandler.validate(ctx, List.of(stockRow(2, "isbn", "111"))))
                .isInstanceOfSatisfying(SalesException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CATALOG_UNAVAILABLE));
    }

    // ------------------------------------------------------------------ mặt hàng: commit

    @Test
    void commitGhiMatHangVaTonDauKyQuaSoCaiADJUSTMENT() {
        UUID a = catalog.add("111", 90_000);
        UUID b = catalog.add("222", 40_000);
        List<ImportRow> valid = stockHandler.validate(ctx, List.of(
                stockRow(2, "isbn", "111", "sellingPrice", "85000", "sku", "sk-1", "supplier", "NXB Trẻ",
                        "costPrice", "60000", "binLocation", "A2", "reorderQuantity", "20", "note", "ghi chú",
                        "lowStockThreshold", "3", "openingQuantity", "30"),
                stockRow(3, "isbn", "222"))); // không có tồn đầu kỳ
        assertThat(valid).allMatch(r -> r.status() == RowStatus.VALID);

        ChunkResult result = stockHandler.commitChunk(ctx, valid);

        assertThat(result.outcomes()).hasSize(2).allMatch(o -> o.status() == OutcomeStatus.CREATED);
        StockItem ia = h.item(a);
        assertThat(ia.onHand()).isEqualTo(30);
        assertThat(ia.sellingPrice().amount()).isEqualTo(85_000);
        assertThat(ia.lowStockThreshold()).isEqualTo(3);
        assertThat(ia.details().sku()).isEqualTo("SK-1");
        assertThat(ia.details().supplier()).isEqualTo("NXB Trẻ");
        assertThat(ia.details().costPrice().amount()).isEqualTo(60_000);
        assertThat(ia.details().binLocation()).isEqualTo("A2");
        assertThat(ia.details().reorderQuantity()).isEqualTo(20);
        assertThat(h.item(b).onHand()).isZero();
        // sổ cái: đúng MỘT biến động ADJUSTMENT +30 cho mặt hàng a, có lý do chứa importId; mặt hàng b không có
        List<StockMovement> ledger = h.ledger();
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).type()).isEqualTo(MovementType.ADJUSTMENT);
        assertThat(ledger.get(0).titleId()).isEqualTo(a);
        assertThat(ledger.get(0).deltaOnHand()).isEqualTo(30);
        assertThat(ledger.get(0).reason()).contains(ctx.importId().toString());
        assertThat(h.stockPort.ledgerTotals(h.tenant, a).onHand()).isEqualTo(ia.onHand()); // sổ cái khớp bộ đếm
        assertThat(h.coverSnapshots).containsEntry(a, 90_000L).containsEntry(b, 40_000L);
    }

    @Test
    void commitBoQuaDongBiNguoiKhacTaoTruocTrongLucCho() {
        UUID a = catalog.add("111", 1);
        UUID b = catalog.add("222", 1);
        List<ImportRow> valid = stockHandler.validate(ctx, List.of(stockRow(2, "isbn", "111", "openingQuantity", "5"),
                stockRow(3, "isbn", "222", "openingQuantity", "7")));
        // giữa preview và confirm, người khác đưa đầu sách a vào bán
        h.catalogTitles.put(a, new CatalogLookupPort.CatalogTitle(a, "x", "y", "111", BigDecimal.ONE));
        h.registerItem.execute(h.tenant, h.user, a, null, null);

        ChunkResult r = stockHandler.commitChunk(ctx, valid);

        assertThat(r.outcomes().get(0).status()).isEqualTo(OutcomeStatus.SKIPPED_DUPLICATE);
        assertThat(r.outcomes().get(1).status()).isEqualTo(OutcomeStatus.CREATED);
        assertThat(h.item(a).onHand()).as("dòng trùng không bị cộng tồn").isZero();
        assertThat(h.item(b).onHand()).isEqualTo(7);
    }

    @Test
    void chunkLoiDoGhiDongThoiThiChayLaiTungDongChiDongViPhamBiBoQua() {
        UUID a = catalog.add("111", 1);
        UUID b = catalog.add("222", 1);
        UUID c = catalog.add("333", 1);
        List<ImportRow> valid = stockHandler.validate(ctx, List.of(stockRow(2, "isbn", "111"),
                stockRow(3, "isbn", "222", "openingQuantity", "4"), stockRow(4, "isbn", "333")));
        // cổng giả: chèn theo lô thì đụng unique (người khác vừa tạo b); chèn từng dòng thì chỉ b bị từ chối
        AtomicInteger batchCalls = new AtomicInteger();
        StockPort racing = (StockPort) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {StockPort.class}, (proxy, method, args) -> {
                    try {
                        if (method.getName().equals("insertAll")) {
                            batchCalls.incrementAndGet();
                            List<StockItem> items = (List<StockItem>) args[0];
                            if (items.size() > 1 || items.get(0).titleId().equals(b)) {
                                throw new SalesException(ErrorCode.STOCK_ITEM_EXISTS, "exists");
                            }
                        }
                        return method.invoke(h.stockPort, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });

        ChunkResult r = stockHandler(racing).commitChunk(ctx, valid);

        assertThat(batchCalls.get()).as("1 lần theo lô + 3 lần từng dòng").isEqualTo(4);
        assertThat(r.outcomes()).extracting(o -> o.status()).containsExactly(OutcomeStatus.CREATED,
                OutcomeStatus.SKIPPED_DUPLICATE, OutcomeStatus.CREATED);
        assertThat(h.item(a)).isNotNull();
        assertThat(h.item(b)).isNull();
        assertThat(h.item(c)).isNotNull();
        assertThat(h.ledger()).as("dòng bị bỏ qua không để lại biến động").isEmpty();
    }

    @Test
    void auditTomTatMotBanGhiMoiLanConfirmKhongChuaGiaNhap() {
        catalog.add("111", 1);
        stockHandler.commitChunk(ctx, stockHandler.validate(ctx, List.of(
                stockRow(2, "isbn", "111", "costPrice", "60000", "note", "bí mật", "openingQuantity", "3"))));
        assertThat(h.audits()).as("commit chunk không audit từng dòng").isEmpty();

        stockHandler.onCompleted(ctx, new ImportResult(ctx.importId(), "stock-items", 1, 0, 0, true));

        assertThat(h.audits()).hasSize(1);
        SalesAuditPort.AuditEntry e = h.audits().get(0);
        assertThat(e.action()).isEqualTo(SalesAuditPort.Actions.STOCK_ITEMS_IMPORTED);
        assertThat(e.aggregateId()).isEqualTo(ctx.importId());
        assertThat(e.actorId()).isEqualTo(h.user);
        assertThat(e.data()).containsEntry("created", 1).containsEntry("skippedDuplicate", 0).containsEntry("failed", 0);
        assertThat(e.data().toString()).doesNotContain("60000").doesNotContain("bí mật");
    }

    // ------------------------------------------------------------------ nhà cung cấp

    @Test
    void nhaCungCapValidateTrungTrongFileVaTrongDb() {
        h.supplierRepo.save(Supplier.create(h.tenant, "NXB Trẻ", null, null, null, null));
        List<ImportRow> out = supplierHandler.validate(ctx, List.of(
                supplierRow(2, "name", "Kim Đồng", "phone", "0901"),
                supplierRow(3, "name", "  kim đồng "), // trùng dòng 2 (không phân biệt hoa thường, bỏ khoảng trắng)
                supplierRow(4, "name", "nxb trẻ"), // đã có trong DB
                supplierRow(5, "name", ""))); // thiếu tên
        assertThat(out).extracting(ImportRow::status).containsExactly(RowStatus.VALID, RowStatus.INVALID,
                RowStatus.DUPLICATE, RowStatus.INVALID);
        assertThat(out.get(1).issues()).anyMatch(i -> i.code().equals(IssueCodes.DUPLICATE_IN_FILE));
        assertThat(out.get(2).issues()).anyMatch(i -> i.code().equals("SUPPLIER_EXISTS"));
    }

    @Test
    void nhaCungCapCommitTaoActiveVaChayLaiTungDongKhiTrung() {
        List<ImportRow> valid = supplierHandler.validate(ctx, List.of(
                supplierRow(2, "name", "A", "email", "a@x.vn", "address", "HN", "note", "n"),
                supplierRow(3, "name", "B")));
        // chen ngang: B vừa được người khác tạo, nên commit lô sẽ báo trùng ở B
        h.supplierRepo.save(Supplier.create(h.tenant, "B", null, null, null, null));

        ChunkResult r = supplierHandler.commitChunk(ctx, valid);

        assertThat(r.outcomes()).extracting(o -> o.status()).containsExactly(OutcomeStatus.CREATED,
                OutcomeStatus.SKIPPED_DUPLICATE);
        assertThat(h.supplierRepo.existsByName(h.tenant, "a", null)).isTrue();
        assertThat(h.supplierRepo.search(h.tenant, null, 0, 10).items()).filteredOn(s -> s.name().equals("A"))
                .singleElement().satisfies(s -> {
                    assertThat(s.active()).isTrue();
                    assertThat(s.email()).isEqualTo("a@x.vn");
                });
        assertThat(h.audits()).isEmpty();
    }

    @Test
    void nhaCungCapOnCompletedGhiMotAuditVaXoaCache() {
        supplierHandler.onCompleted(ctx, new ImportResult(ctx.importId(), "suppliers", 2, 1, 0, true));
        assertThat(h.audits()).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo(SalesAuditPort.Actions.SUPPLIERS_IMPORTED);
            assertThat(e.data()).containsEntry("created", 2).containsEntry("skippedDuplicate", 1);
        });
        assertThat(cacheEvictions.get()).isEqualTo(1);
    }

    @Test
    void khoaTenLoaiKhopHopDong() {
        assertThat(stockHandler.type()).isEqualTo("stock-items");
        assertThat(supplierHandler.type()).isEqualTo("suppliers");
        assertThat(stockHandler.schema().sheets().get(0).columns()).extracting(c -> c.key()).containsExactly("isbn",
                "sellingPrice", "lowStockThreshold", "sku", "supplier", "costPrice", "binLocation", "reorderQuantity",
                "note", "openingQuantity");
        assertThat(supplierHandler.schema().sheets().get(0).columns()).extracting(c -> c.key())
                .containsExactly("name", "phone", "email", "address", "note");
    }
}
