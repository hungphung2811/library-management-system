package lms.sales.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.GoodsReceiptUseCases.LineData;
import lms.sales.stock.application.SupplierUseCases.SupplierData;
import lms.sales.stock.domain.StockItemDetails;
import lms.sales.support.Harness;
import org.junit.jupiter.api.Test;

/** Chi tiết quản lý mặt hàng (sku, nhà cung cấp, giá nhập, vị trí kho, đặt lại, lần nhập gần nhất, ghi chú). */
class StockItemDetailsTest {

    private final Harness h = new Harness();

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    @Test
    void chuanHoaSkuVaChuoiRong() {
        var d = StockItemDetails.of("  lms-2020-0001 ", " ", 55_000L, "", null, null, "  ");
        assertEquals("LMS-2020-0001", d.sku());
        assertNull(d.supplier());
        assertNull(d.binLocation());
        assertNull(d.note());
        assertEquals(55_000, d.costPrice().amount());
    }

    @Test
    void kiemDoDaiVaDau() {
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> StockItemDetails.of("x".repeat(41), null, null, null,
                null, null, null)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> StockItemDetails.of(null, "x".repeat(256), null, null,
                null, null, null)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> StockItemDetails.of(null, null, null, "x".repeat(65),
                null, null, null)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> StockItemDetails.of(null, null, null, null, null, null,
                "x".repeat(501))));
        assertEquals(ErrorCode.INVALID_PRICE, codeOf(() -> StockItemDetails.of(null, null, -1L, null, null, null,
                null)));
        assertEquals(ErrorCode.INVALID_QUANTITY, codeOf(() -> StockItemDetails.of(null, null, null, null, -1, null,
                null)));
        // đúng giới hạn thì qua
        StockItemDetails.of("x".repeat(40), "x".repeat(255), 0L, "x".repeat(64), 0, null, "x".repeat(500));
    }

    @Test
    void toStringKhongInGiaNhapVaGhiChu() {
        var d = StockItemDetails.of("SKU-1", "NCC", 12_345L, "K1-A1", 5, null, "ghi chu noi bo");
        assertFalse(d.toString().contains("12345"));
        assertFalse(d.toString().contains("ghi chu"));
    }

    @Test
    void fillMissingKhongGhiDeGiaTriCo() {
        var cur = StockItemDetails.of("A-1", null, 10_000L, null, null, null, null);
        var want = StockItemDetails.of("B-2", "Fahasa", 99_000L, "K1-A1", 20, Instant.EPOCH, "n");
        var merged = cur.fillMissingFrom(want);
        assertEquals("A-1", merged.sku());
        assertEquals(10_000, merged.costPrice().amount());
        assertEquals("Fahasa", merged.supplier());
        assertEquals("K1-A1", merged.binLocation());
        assertEquals(20, merged.reorderQuantity());
        assertEquals(merged, merged.fillMissingFrom(want)); // chạy lại không đổi
    }

    @Test
    void dangKyKemChiTietVaSkuTrungBi409() {
        UUID a = h.book("A", 100_000, null, 0);
        UUID b = UUID.randomUUID();
        h.catalogTitles.put(b, new lms.sales.stock.application.CatalogLookupPort.CatalogTitle(b, "B", "T", "I",
                java.math.BigDecimal.TEN));
        UUID c = UUID.randomUUID();
        h.catalogTitles.put(c, new lms.sales.stock.application.CatalogLookupPort.CatalogTitle(c, "C", "T", "I",
                java.math.BigDecimal.TEN));
        var created = h.registerItem.execute(h.tenant, h.user, b, 50_000L, null,
                StockItemDetails.of(" lms-2024-0001", "Fahasa", 30_000L, "K1-A3", 20, null, "ghi chu"));
        assertEquals("LMS-2024-0001", created.item().details().sku());
        assertEquals("Fahasa", created.item().details().supplier());
        assertEquals(ErrorCode.SKU_EXISTS, codeOf(() -> h.registerItem.execute(h.tenant, h.user, c, null, null,
                StockItemDetails.of("LMS-2024-0001", null, null, null, null, null, null))));
        // mặt hàng không có chi tiết vẫn dùng được
        assertTrue(h.item(a).details().sku() == null);
    }

    @Test
    void capNhatChiTietThayToanBoGiuLastRestockedKhiVangVaAuditKhongLoGiaTri() {
        UUID a = h.book("A", 100_000, null, 0);
        UUID b = h.book("B", 100_000, null, 0);
        Instant t = Instant.parse("2026-11-01T03:00:00Z");
        h.itemDetails.update(h.tenant, h.user, a, StockItemDetails.of("SKU-A", "Nhã Nam", 60_000L, "K1-B2", 10, t,
                "ban chay"));
        var saved = h.itemDetails.update(h.tenant, h.user, a, StockItemDetails.of("SKU-A", null, 61_000L, null, null,
                null, null));
        assertEquals(t, saved.details().lastRestockedAt()); // vắng thì giữ nguyên
        assertNull(saved.details().supplier());             // trường khác vắng thì xóa
        assertEquals(61_000, saved.details().costPrice().amount());

        assertEquals(ErrorCode.SKU_EXISTS, codeOf(() -> h.itemDetails.update(h.tenant, h.user, b,
                StockItemDetails.of("sku-a", null, null, null, null, null, null))));
        // giữ SKU của chính mình thì không báo trùng
        h.itemDetails.update(h.tenant, h.user, a, StockItemDetails.of("SKU-A", null, null, null, null, null, null));
        assertEquals(ErrorCode.STOCK_ITEM_NOT_FOUND, codeOf(() -> h.itemDetails.update(h.tenant, h.user,
                UUID.randomUUID(), StockItemDetails.EMPTY)));

        String audits = h.audits().toString();
        assertTrue(h.audits().stream().anyMatch(e -> "STOCK_ITEM_DETAILS_UPDATED".equals(e.action())));
        assertFalse(audits.contains("60000") || audits.contains("61000") || audits.contains("ban chay")
                || audits.contains("Nhã Nam") || audits.contains("K1-B2"), "audit chỉ ghi tên trường: " + audits);
    }

    @Test
    void xacNhanPhieuNhapCapNhatLastRestockedAt() {
        UUID a = h.book("A", 1_000, null, 0);
        UUID b = h.book("B", 1_000, null, 0);
        var supplier = h.supplierUseCases.create(h.tenant, h.user, new SupplierData("NXB Kim Đồng", "0900000000",
                null, null, null));
        var receipt = h.receiptUseCases.create(h.tenant, h.user, supplier.id(), List.of(new LineData(a, 10, 40_000)),
                UUID.randomUUID());
        assertNull(h.item(a).details().lastRestockedAt()); // nháp chưa tính
        h.receiptUseCases.confirm(h.tenant, h.user, receipt.id());
        assertEquals(h.clock.instant(), h.item(a).details().lastRestockedAt());
        assertNull(h.item(b).details().lastRestockedAt()); // mặt hàng không có trong phiếu không đổi
    }
}
