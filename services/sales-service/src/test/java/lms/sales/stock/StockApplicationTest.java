package lms.sales.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.GoodsReceiptUseCases.LineData;
import lms.sales.stock.application.StockLine;
import lms.sales.stock.application.SupplierUseCases.SupplierData;
import lms.sales.stock.domain.MovementType;
import lms.sales.stock.domain.ReceiptStatus;
import lms.sales.stock.domain.StockMovement;
import lms.sales.support.Harness;
import org.junit.jupiter.api.Test;

class StockApplicationTest {

    private final Harness h = new Harness();

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    private void assertReconciled(UUID titleId) {
        var r = h.reconcile.execute(h.tenant, titleId);
        assertTrue(r.consistent(), "on_hand/reserved phải bằng tổng sổ cái: " + r);
    }

    @Test
    void registerRequiresCatalogTitleAndIsUnique() {
        UUID unknown = UUID.randomUUID();
        assertEquals(ErrorCode.TITLE_NOT_AVAILABLE, codeOf(() -> h.registerItem.execute(h.tenant, h.user, unknown,
                null, null)));
        UUID id = h.book("Dế Mèn", 85_000, null, 0);
        assertEquals(ErrorCode.STOCK_ITEM_EXISTS, codeOf(() -> h.registerItem.execute(h.tenant, h.user, id, null,
                null)));
        UUID fresh = UUID.randomUUID();
        h.catalogTitles.put(fresh, new lms.sales.stock.application.CatalogLookupPort.CatalogTitle(fresh, "X", "T",
                "I", java.math.BigDecimal.ONE));
        var view = h.registerItem.execute(h.tenant, h.user, fresh, 50_000L, 2);
        assertEquals(50_000, view.effectivePrice().amount());
    }

    @Test
    void effectivePriceFallsBackToCoverPrice() {
        UUID id = h.book("Tắt đèn", 85_000, null, 0);
        UUID id2 = h.book("Chí Phèo", 70_000, 60_000L, 0);
        assertEquals(85_000, h.getItem.execute(h.tenant, id).effectivePrice().amount()); // giá bìa
        assertEquals(60_000, h.getItem.execute(h.tenant, id2).effectivePrice().amount()); // giá bán riêng
        assertEquals("Tắt đèn", h.getItem.execute(h.tenant, id).titleName());
        assertEquals(ErrorCode.STOCK_ITEM_NOT_FOUND, codeOf(() -> h.getItem.execute(h.tenant, UUID.randomUUID())));
    }

    @Test
    void reserveReleaseFulfilRestockKeepLedgerConsistent() {
        UUID a = h.book("A", 10_000, null, 10);
        h.stockService.reserveAll(h.tenant, h.user, List.of(new StockLine(a, 3)), "ORDER", UUID.randomUUID());
        assertEquals(7, h.item(a).available());
        assertEquals(10, h.item(a).onHand());

        // bán 8 vượt available 7 dù onHand = 10
        var ex = assertThrows(SalesException.class, () -> h.stockService.sellAll(h.tenant, h.user,
                List.of(new StockLine(a, 8)), "ORDER", UUID.randomUUID()));
        assertEquals(ErrorCode.INSUFFICIENT_STOCK, ex.code());
        assertEquals(7L, ex.details().get("available"));

        h.stockService.fulfilAll(h.tenant, h.user, List.of(new StockLine(a, 3)), "ORDER", UUID.randomUUID());
        assertEquals(7, h.item(a).onHand());
        assertEquals(0, h.item(a).reserved());
        h.stockService.cancelRestockAll(h.tenant, h.user, List.of(new StockLine(a, 3)), "ORDER", UUID.randomUUID());
        assertEquals(10, h.item(a).onHand());

        h.stockService.reserveAll(h.tenant, h.user, List.of(new StockLine(a, 2)), "ORDER", UUID.randomUUID());
        h.stockService.releaseAll(h.tenant, h.user, List.of(new StockLine(a, 2)), "ORDER", UUID.randomUUID());
        assertEquals(0, h.item(a).reserved());
        // release vượt reserved bị chặn
        assertEquals(ErrorCode.INSUFFICIENT_STOCK, codeOf(() -> h.stockService.releaseAll(h.tenant, h.user,
                List.of(new StockLine(a, 1)), "ORDER", UUID.randomUUID())));
        assertReconciled(a);
    }

    @Test
    void sellMergesDuplicateTitlesAndProcessesAscending() {
        UUID a = h.book("A", 1_000, null, 10);
        UUID b = h.book("B", 1_000, null, 10);
        UUID lo = a.compareTo(b) < 0 ? a : b;
        UUID hi = lo.equals(a) ? b : a;
        int before = h.ledger().size();
        h.stockService.sellAll(h.tenant, h.user, List.of(new StockLine(hi, 1), new StockLine(lo, 2),
                new StockLine(hi, 3)), "ORDER", UUID.randomUUID());
        List<StockMovement> sales = h.ledger().subList(before, h.ledger().size());
        assertEquals(2, sales.size()); // dòng trùng được gộp
        assertEquals(lo, sales.get(0).titleId());
        assertEquals(hi, sales.get(1).titleId());
        assertEquals(-4, sales.get(1).deltaOnHand());
    }

    @Test
    void sellOfUnregisteredTitleIsRejected() {
        assertEquals(ErrorCode.STOCK_ITEM_NOT_REGISTERED, codeOf(() -> h.stockService.sellAll(h.tenant, h.user,
                List.of(new StockLine(UUID.randomUUID(), 1)), "ORDER", UUID.randomUUID())));
    }

    @Test
    void adjustRules() {
        UUID a = h.book("A", 1_000, null, 5);
        assertEquals(ErrorCode.REASON_REQUIRED, codeOf(() -> h.adjust.execute(h.tenant, h.user, a, 1, " ")));
        assertEquals(ErrorCode.INVALID_QUANTITY, codeOf(() -> h.adjust.execute(h.tenant, h.user, a, 0, "x")));
        assertEquals(ErrorCode.INSUFFICIENT_STOCK, codeOf(() -> h.adjust.execute(h.tenant, h.user, a, -6, "mất")));
        h.adjust.execute(h.tenant, h.user, a, -2, "hỏng do nước");
        assertEquals(3, h.item(a).onHand());
        assertEquals(MovementType.ADJUSTMENT, h.ledger().get(h.ledger().size() - 1).type());
        assertEquals("hỏng do nước", h.ledger().get(h.ledger().size() - 1).reason());
        assertReconciled(a);
    }

    @Test
    void stocktakeWritesDifferenceAndRespectsReserved() {
        UUID a = h.book("A", 1_000, null, 10);
        h.stockService.reserveAll(h.tenant, h.user, List.of(new StockLine(a, 4)), "ORDER", UUID.randomUUID());
        // đếm 3 < reserved 4: sai
        assertEquals(ErrorCode.INVALID_QUANTITY, codeOf(() -> h.stocktake.execute(h.tenant, h.user, a, 3, "đếm")));
        assertEquals(ErrorCode.REASON_REQUIRED, codeOf(() -> h.stocktake.execute(h.tenant, h.user, a, 8, "")));
        assertEquals(-2, h.stocktake.execute(h.tenant, h.user, a, 8, "thiếu 2 trên kệ"));
        assertEquals(8, h.item(a).onHand());
        assertEquals(4, h.item(a).reserved());
        // kiểm khớp: delta 0 vẫn ghi sổ cái
        int n = h.ledger().size();
        assertEquals(0, h.stocktake.execute(h.tenant, h.user, a, 8, "khớp"));
        assertEquals(n + 1, h.ledger().size());
        assertReconciled(a);
    }

    @Test
    void goodsReceiptConfirmIncreasesStockOnceAndWritesLedger() {
        UUID a = h.book("A", 1_000, null, 0);
        UUID b = h.book("B", 1_000, null, 2);
        var supplier = h.supplierUseCases.create(h.tenant, h.user, new SupplierData("NXB Kim Đồng", "0900000000",
                null, null, null));
        UUID cid = UUID.randomUUID();
        var receipt = h.receiptUseCases.create(h.tenant, h.user, supplier.id(),
                List.of(new LineData(a, 10, 40_000), new LineData(b, 5, 20_000)), cid);
        assertEquals(ReceiptStatus.DRAFT, receipt.status());
        assertEquals(0, h.item(a).onHand()); // nháp chưa tăng tồn

        // gửi lại cùng clientRequestId trả đúng phiếu cũ
        var again = h.receiptUseCases.create(h.tenant, h.user, supplier.id(),
                List.of(new LineData(a, 99, 1)), cid);
        assertEquals(receipt.id(), again.id());

        var confirmed = h.receiptUseCases.confirm(h.tenant, h.user, receipt.id());
        assertEquals(ReceiptStatus.CONFIRMED, confirmed.status());
        assertEquals(10, h.item(a).onHand());
        assertEquals(7, h.item(b).onHand());
        assertEquals(MovementType.RECEIPT, h.ledger().get(h.ledger().size() - 1).type());

        assertEquals(ErrorCode.RECEIPT_STATE_CONFLICT, codeOf(() -> h.tx(() -> h.receiptUseCases.confirm(h.tenant,
                h.user, receipt.id()))));
        assertEquals(10, h.item(a).onHand()); // không cộng lần hai
        assertEquals(ErrorCode.RECEIPT_STATE_CONFLICT, codeOf(() -> h.receiptUseCases.cancel(h.tenant, h.user,
                receipt.id())));
        assertReconciled(a);
        assertReconciled(b);
    }

    @Test
    void goodsReceiptCancelAndValidation() {
        UUID a = h.book("A", 1_000, null, 0);
        var supplier = h.supplierUseCases.create(h.tenant, h.user, new SupplierData("NCC", null, null, null, null));
        var r = h.receiptUseCases.create(h.tenant, h.user, supplier.id(), List.of(new LineData(a, 1, 0)),
                UUID.randomUUID());
        assertEquals(ReceiptStatus.CANCELLED, h.receiptUseCases.cancel(h.tenant, h.user, r.id()).status());
        assertEquals(ErrorCode.RECEIPT_STATE_CONFLICT, codeOf(() -> h.receiptUseCases.confirm(h.tenant, h.user,
                r.id())));

        UUID notRegistered = UUID.randomUUID();
        assertEquals(ErrorCode.STOCK_ITEM_NOT_REGISTERED, codeOf(() -> h.receiptUseCases.create(h.tenant, h.user,
                supplier.id(), List.of(new LineData(notRegistered, 1, 0)), UUID.randomUUID())));
        assertEquals(ErrorCode.SUPPLIER_NOT_FOUND, codeOf(() -> h.receiptUseCases.create(h.tenant, h.user,
                UUID.randomUUID(), List.of(new LineData(a, 1, 0)), UUID.randomUUID())));
        assertEquals(ErrorCode.INVALID_QUANTITY, codeOf(() -> h.receiptUseCases.create(h.tenant, h.user,
                supplier.id(), List.of(new LineData(a, 0, 0)), UUID.randomUUID())));
        assertEquals(ErrorCode.RECEIPT_NOT_FOUND, codeOf(() -> h.receiptUseCases.get(h.tenant, UUID.randomUUID())));
    }

    @Test
    void confirmRunsInsideSimulatedTransaction() {
        UUID a = h.book("A", 1_000, null, 0);
        var supplier = h.supplierUseCases.create(h.tenant, h.user, new SupplierData("NCC", null, null, null, null));
        var r = h.receiptUseCases.create(h.tenant, h.user, supplier.id(), List.of(new LineData(a, 4, 0)),
                UUID.randomUUID());
        h.tx(() -> h.receiptUseCases.confirm(h.tenant, h.user, r.id()));
        assertEquals(4, h.item(a).onHand());
    }

    @Test
    void supplierRules() {
        var s = h.supplierUseCases.create(h.tenant, h.user, new SupplierData("  Fahasa ", "", null, null, null));
        assertEquals("Fahasa", s.name());
        assertEquals(ErrorCode.SUPPLIER_EXISTS, codeOf(() -> h.supplierUseCases.create(h.tenant, h.user,
                new SupplierData("fahasa", null, null, null, null))));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> h.supplierUseCases.create(h.tenant, h.user,
                new SupplierData(" ", null, null, null, null))));
        var other = h.supplierUseCases.create(h.tenant, h.user, new SupplierData("Nhã Nam", null, null, null, null));
        assertEquals(ErrorCode.SUPPLIER_EXISTS, codeOf(() -> h.supplierUseCases.update(h.tenant, h.user,
                other.id(), new SupplierData("FAHASA", null, null, null, null), true)));
        var inactive = h.supplierUseCases.update(h.tenant, h.user, other.id(),
                new SupplierData("Nhã Nam", null, null, null, null), false);
        assertFalse(inactive.active());
        UUID a = h.book("A", 1, null, 0);
        // nhà cung cấp ngừng hoạt động không nhập được
        assertEquals(ErrorCode.SUPPLIER_NOT_FOUND, codeOf(() -> h.receiptUseCases.create(h.tenant, h.user,
                other.id(), List.of(new LineData(a, 1, 0)), UUID.randomUUID())));
        assertSame(1, h.supplierUseCases.search(h.tenant, "fah", 0, 10).items().size());
    }

    @Test
    void ledgerStaysEqualToCountersAfterRandomOperations() {
        Random rnd = new Random(7);
        UUID a = h.book("A", 1_000, null, 20);
        UUID b = h.book("B", 1_000, null, 20);
        UUID[] ids = {a, b};
        for (int i = 0; i < 600; i++) {
            UUID t = ids[rnd.nextInt(2)];
            int q = 1 + rnd.nextInt(5);
            try {
                h.txRun(() -> {
                    switch (rnd.nextInt(7)) {
                        case 0 -> h.stockService.sellAll(h.tenant, h.user, List.of(new StockLine(t, q)), "O", null);
                        case 1 -> h.stockService.reserveAll(h.tenant, h.user, List.of(new StockLine(t, q)), "O",
                                null);
                        case 2 -> h.stockService.releaseAll(h.tenant, h.user, List.of(new StockLine(t, q)), "O",
                                null);
                        case 3 -> h.stockService.fulfilAll(h.tenant, h.user, List.of(new StockLine(t, q)), "O",
                                null);
                        case 4 -> h.stockService.receiveAll(h.tenant, h.user, List.of(new StockLine(t, q)), "R",
                                null);
                        case 5 -> h.adjust.execute(h.tenant, h.user, t, rnd.nextInt(7) - 3, "kiểm");
                        default -> h.stocktake.execute(h.tenant, h.user, t, h.item(t).reserved() + rnd.nextInt(15),
                                "đếm");
                    }
                });
            } catch (SalesException expected) {
                // từ chối hợp lệ (vượt tồn...), trạng thái đã rollback
            }
            for (UUID id : ids) {
                var item = h.item(id);
                assertTrue(item.onHand() >= 0 && item.reserved() >= 0 && item.reserved() <= item.onHand());
                assertReconciled(id);
            }
        }
    }
}
