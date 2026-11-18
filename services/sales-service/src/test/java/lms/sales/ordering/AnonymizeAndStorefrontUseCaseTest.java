package lms.sales.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.sales.ordering.application.AnonymizeWalkInBatchUseCase;
import lms.sales.ordering.application.AnonymizeWalkInContactsUseCase;
import lms.sales.ordering.application.WalkInAnonymizationPort;
import lms.sales.settings.application.UpdateSalesSettingsCommand;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.StorefrontQueryUseCases;
import lms.sales.support.Harness;
import org.junit.jupiter.api.Test;

/** B2.5 (theo lô, idempotent, audit không PII, cấu hình theo tenant) và danh mục công khai (không lộ tồn/ISBN). */
class AnonymizeAndStorefrontUseCaseTest {

    /** Cổng giả: danh sách đơn đến hạn và đếm lần anonymize; đơn đã ẩn danh không được chọn lại (idempotent). */
    private static final class FakePort implements WalkInAnonymizationPort {
        final List<UUID> pending = new ArrayList<>();
        final List<Instant> cutoffs = new ArrayList<>();
        int batches;

        @Override
        public List<UUID> findDue(UUID tenantId, Instant cutoff, int limit) {
            cutoffs.add(cutoff);
            return List.copyOf(pending.subList(0, Math.min(limit, pending.size())));
        }

        @Override
        public int anonymize(UUID tenantId, List<UUID> orderIds, Instant now) {
            batches++;
            pending.removeAll(orderIds);
            return orderIds.size();
        }
    }

    private final Harness h = new Harness();
    private final FakePort port = new FakePort();
    private final List<SalesAuditPort.AuditEntry> audits = new ArrayList<>();
    private final AnonymizeWalkInContactsUseCase useCase = new AnonymizeWalkInContactsUseCase(h.getSettings,
            new AnonymizeWalkInBatchUseCase(port, audits::add), h.clock, 2);

    @Test
    void anDanhTheoLoRoiChayLaiKhongDoiGiVaAuditKhongCoPii() {
        for (int i = 0; i < 5; i++) {
            port.pending.add(UUID.randomUUID());
        }
        assertEquals(5, useCase.execute(h.tenant));
        assertEquals(3, port.batches); // 2 + 2 + 1
        assertEquals(3, audits.size());
        for (var a : audits) {
            assertEquals(SalesAuditPort.Actions.WALK_IN_PII_ANONYMIZED, a.action());
            assertEquals(null, a.actorId()); // hệ thống
            assertTrue(a.data().keySet().stream().allMatch(k -> k.equals("orders") || k.equals("cutoff")));
        }
        assertEquals(5, audits.stream().mapToInt(a -> (Integer) a.data().get("orders")).sum());
        // chạy lại: 0 đơn, không audit mới
        assertEquals(0, useCase.execute(h.tenant));
        assertEquals(3, audits.size());
    }

    @Test
    void mocCatTheoCauHinhCuaTenantMacDinh12Thang() {
        port.pending.add(UUID.randomUUID());
        useCase.execute(h.tenant);
        assertEquals(Instant.parse("2025-11-17T03:00:00Z"), port.cutoffs.get(0)); // mặc định 12 tháng
        h.updateSettings.execute(new UpdateSalesSettingsCommand(h.tenant, h.user, 500, true, 7, 0, 5, 3, 0));
        port.pending.add(UUID.randomUUID());
        port.cutoffs.clear();
        useCase.execute(h.tenant);
        assertEquals(Instant.parse("2026-08-17T03:00:00Z"), port.cutoffs.get(0)); // 3 tháng
    }

    @Test
    void khongCoDonDenHanThiKhongGhiAudit() {
        assertEquals(0, useCase.execute(h.tenant));
        assertTrue(audits.isEmpty());
    }

    // ---- storefront ----

    private UUID sellable(String name, long price, long stock) {
        return h.book(name, price, null, stock);
    }

    @Test
    void danhMucChiCoInStockKhongLoTonIsbnHayGiaNhap() {
        UUID a = sellable("Sách A", 100_000, 3);
        UUID b = sellable("Sách B", 50_000, 0);
        var page = h.storefront.list(h.tenant, null, null, null, 0, 20);
        assertEquals(2, page.total());
        var va = page.items().stream().filter(v -> v.titleId().equals(a)).findFirst().orElseThrow();
        var vb = page.items().stream().filter(v -> v.titleId().equals(b)).findFirst().orElseThrow();
        assertTrue(va.inStock());
        assertFalse(vb.inStock());
        assertEquals(100_000, va.price());
        // bản ghi công khai không có trường nhạy cảm
        for (var c : StorefrontQueryUseCases.ItemView.class.getRecordComponents()) {
            assertFalse(List.of("onHand", "reserved", "available", "isbn", "tenantId", "costPrice",
                    "lowStockThreshold").contains(c.getName()), c.getName());
        }
        for (var c : StorefrontQueryUseCases.ItemDetailView.class.getRecordComponents()) {
            assertFalse(List.of("onHand", "reserved", "available", "isbn", "tenantId", "previewText")
                    .contains(c.getName()), c.getName());
        }
    }

    @Test
    void chiMatHangActiveVaGiaUuTienSellingPrice() {
        UUID a = h.book("Sách A", 100_000, 80_000L, 2);
        UUID off = sellable("Sách Tắt", 10_000, 2);
        h.stockPort.saveAttributes(h.item(off).withActive(false));
        var page = h.storefront.list(h.tenant, null, null, null, 0, 20);
        assertEquals(1, page.total());
        assertEquals(80_000, page.items().get(0).price());
        assertEquals(ErrorCode.STOCK_ITEM_NOT_FOUND,
                assertThrows(SalesException.class, () -> h.storefront.get(h.tenant, off)).code());
        assertEquals(ErrorCode.STOCK_ITEM_NOT_FOUND,
                assertThrows(SalesException.class, () -> h.storefront.preview(h.tenant, off)).code());
        assertEquals(80_000, h.storefront.get(h.tenant, a).price());
    }

    @Test
    void trangToiDa50VaMotLanGoiCatalogMoiTrang() {
        for (int i = 0; i < 60; i++) {
            sellable("Sách " + i, 1_000, 1);
        }
        h.catalogCalls = 0;
        var page = h.storefront.list(h.tenant, null, null, null, 0, 500);
        assertEquals(50, page.items().size());
        assertEquals(50, page.size());
        assertEquals(60, page.total());
        assertEquals(1, h.catalogCalls); // không N+1
    }

    @Test
    void tuKhoaGiaoVoiMatHangDangBanVaPreviewChiQuaEndpointRieng() {
        UUID a = sellable("Dế Mèn", 100_000, 1);
        sellable("Tôi Thấy Hoa Vàng", 90_000, 1);
        var found = h.storefront.list(h.tenant, "dế", null, null, 0, 20);
        assertEquals(1, found.total());
        assertEquals(a, found.items().get(0).titleId());
        assertEquals(0, h.storefront.list(h.tenant, "không có", null, null, 0, 20).total());

        h.catalogPreviews.put(a, new CatalogLookupPort.CatalogPreview(a, "Dế Mèn", "Đoạn đọc thử ..."));
        assertEquals("Đoạn đọc thử ...", h.storefront.preview(h.tenant, a).previewText());
        var other = UUID.randomUUID();
        assertEquals(ErrorCode.STOCK_ITEM_NOT_FOUND,
                assertThrows(SalesException.class, () -> h.storefront.preview(h.tenant, other)).code());
    }
}
