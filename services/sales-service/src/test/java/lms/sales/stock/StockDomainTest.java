package lms.sales.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.GoodsReceipt;
import lms.sales.stock.domain.GoodsReceiptLine;
import lms.sales.stock.domain.MovementType;
import lms.sales.stock.domain.ReceiptStatus;
import lms.sales.stock.domain.StockItem;
import lms.sales.stock.domain.StockMovement;
import org.junit.jupiter.api.Test;

class StockDomainTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID title = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-17T00:00:00Z");

    private StockItem item(long on, long res) {
        return StockItem.restore(UUID.randomUUID(), tenant, title, on, res, null, null, true, 0);
    }

    @Test
    void applyKeepsInvariantAndNeverGoesNegative() {
        StockItem s = item(5, 2);
        assertEquals(3, s.available());
        assertEquals(2, s.apply(-3, 0).onHand());           // bán 3 (available 3)
        var e = assertThrows(SalesException.class, () -> s.apply(-4, 0)); // 4 > available
        assertEquals(ErrorCode.INSUFFICIENT_STOCK, e.code());
        assertEquals(3L, e.details().get("available"));
        assertThrows(SalesException.class, () -> s.apply(0, 4));          // giữ 4 > available
        assertThrows(SalesException.class, () -> s.apply(0, -3));         // reserved âm
        assertEquals(0, s.apply(-3, -2).reserved()); // fulfil hợp lệ
        assertEquals(5, s.apply(0, 3).reserved());
    }

    @Test
    void lowStockUsesItemThresholdThenTenantThreshold() {
        assertTrue(item(5, 0).isLowStock(5));
        assertFalse(item(6, 0).isLowStock(5));
        StockItem custom = StockItem.restore(UUID.randomUUID(), tenant, title, 8, 0, null, 10, true, 0);
        assertTrue(custom.isLowStock(5));
    }

    @Test
    void movementReasonAndSigns() {
        assertThrows(SalesException.class, () -> StockMovement.of(tenant, title, MovementType.ADJUSTMENT, 3, 0,
                "ADJUSTMENT", null, " ", actor, now));
        assertThrows(SalesException.class, () -> StockMovement.of(tenant, title, MovementType.WRITE_OFF, -1, 0,
                "RETURN", null, null, actor, now));
        assertThrows(IllegalArgumentException.class, () -> StockMovement.of(tenant, title, MovementType.SALE, 1, 0,
                "ORDER", null, null, actor, now));
        assertThrows(IllegalArgumentException.class, () -> StockMovement.of(tenant, title, MovementType.FULFIL, -2,
                -1, "ORDER", null, null, actor, now));
        StockMovement.of(tenant, title, MovementType.FULFIL, -2, -2, "ORDER", null, null, actor, now);
        StockMovement.of(tenant, title, MovementType.RESERVE, 0, 2, "ORDER", null, null, actor, now);
        StockMovement.of(tenant, title, MovementType.STOCKTAKE, 0, 0, "STOCKTAKE", null, "đếm khớp", actor, now);
    }

    @Test
    void goodsReceiptLifecycle() {
        var lines = List.of(new GoodsReceiptLine(title, 10, Money.of(30_000)));
        GoodsReceipt r = GoodsReceipt.draft(tenant, "R-1", UUID.randomUUID(), lines, UUID.randomUUID(), actor, now);
        assertEquals(300_000, r.totalCost().amount());
        r.confirm(actor, now);
        assertEquals(ReceiptStatus.CONFIRMED, r.status());
        var e = assertThrows(SalesException.class, r::cancel);
        assertEquals(ErrorCode.RECEIPT_STATE_CONFLICT, e.code());
        assertThrows(SalesException.class, () -> r.confirm(actor, now));
    }

    @Test
    void goodsReceiptValidation() {
        assertThrows(SalesException.class, () -> new GoodsReceiptLine(title, 0, Money.of(1)));
        assertThrows(SalesException.class, () -> GoodsReceipt.draft(tenant, "R", UUID.randomUUID(), List.of(),
                UUID.randomUUID(), actor, now));
        var dup = List.of(new GoodsReceiptLine(title, 1, Money.ZERO), new GoodsReceiptLine(title, 2, Money.ZERO));
        assertThrows(SalesException.class, () -> GoodsReceipt.draft(tenant, "R", UUID.randomUUID(), dup,
                UUID.randomUUID(), actor, now));
    }
}
