package lms.sales.stock.domain;

import java.util.UUID;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;

/**
 * Mặt hàng tồn kho (một dòng mỗi đầu sách mỗi tenant). Hai bộ đếm: onHand (hàng thực có) và reserved (đã giữ cho
 * đơn online chưa giao); available = onHand - reserved. Bất biến: onHand >= 0, reserved >= 0, reserved <= onHand.
 * Mọi thay đổi bộ đếm đi qua {@link #apply}; adapter lưu trữ lặp lại cùng điều kiện trong MỘT câu UPDATE.
 */
public final class StockItem {

    private final UUID id;
    private final UUID tenantId;
    private final UUID titleId;
    private final long onHand;
    private final long reserved;
    private final Money sellingPrice;
    private final Integer lowStockThreshold;
    private final boolean active;
    private final long version;
    private final StockItemDetails details;

    private StockItem(UUID id, UUID tenantId, UUID titleId, long onHand, long reserved, Money sellingPrice,
                      Integer lowStockThreshold, boolean active, long version, StockItemDetails details) {
        if (onHand < 0 || reserved < 0 || reserved > onHand) {
            throw new IllegalStateException("Vi phạm bất biến tồn kho: onHand=" + onHand + ", reserved=" + reserved);
        }
        if (lowStockThreshold != null && lowStockThreshold < 0) {
            throw SalesException.invalidQuantity("Ngưỡng tồn thấp không được âm");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.titleId = titleId;
        this.onHand = onHand;
        this.reserved = reserved;
        this.sellingPrice = sellingPrice;
        this.lowStockThreshold = lowStockThreshold;
        this.active = active;
        this.version = version;
        this.details = details == null ? StockItemDetails.EMPTY : details;
    }

    /** Mặt hàng mới đưa vào bán: tồn 0. */
    public static StockItem register(UUID tenantId, UUID titleId, Money sellingPrice, Integer lowStockThreshold) {
        return register(tenantId, titleId, sellingPrice, lowStockThreshold, null);
    }

    /** Như trên, kèm thông tin quản lý (SKU, nhà cung cấp, giá nhập...). */
    public static StockItem register(UUID tenantId, UUID titleId, Money sellingPrice, Integer lowStockThreshold,
                                     StockItemDetails details) {
        return new StockItem(UUID.randomUUID(), tenantId, titleId, 0, 0, sellingPrice, lowStockThreshold, true, 0,
                details);
    }

    public static StockItem restore(UUID id, UUID tenantId, UUID titleId, long onHand, long reserved,
                                    Money sellingPrice, Integer lowStockThreshold, boolean active, long version) {
        return restore(id, tenantId, titleId, onHand, reserved, sellingPrice, lowStockThreshold, active, version,
                null);
    }

    public static StockItem restore(UUID id, UUID tenantId, UUID titleId, long onHand, long reserved,
                                    Money sellingPrice, Integer lowStockThreshold, boolean active, long version,
                                    StockItemDetails details) {
        return new StockItem(id, tenantId, titleId, onHand, reserved, sellingPrice, lowStockThreshold, active,
                version, details);
    }

    /** Áp delta; ném INSUFFICIENT_STOCK (kèm available hiện tại) nếu kết quả vi phạm bất biến. */
    public StockItem apply(long deltaOnHand, long deltaReserved) {
        long newOnHand = onHand + deltaOnHand;
        long newReserved = reserved + deltaReserved;
        if (newOnHand < 0 || newReserved < 0 || newReserved > newOnHand) {
            throw SalesException.insufficientStock(titleId, available());
        }
        return new StockItem(id, tenantId, titleId, newOnHand, newReserved, sellingPrice, lowStockThreshold, active,
                version, details);
    }

    public StockItem withSellingPrice(Money price) {
        return new StockItem(id, tenantId, titleId, onHand, reserved, price, lowStockThreshold, active, version,
                details);
    }

    public StockItem withDetails(StockItemDetails newDetails) {
        return new StockItem(id, tenantId, titleId, onHand, reserved, sellingPrice, lowStockThreshold, active, version,
                newDetails);
    }

    public StockItem withActive(boolean newActive) {
        return new StockItem(id, tenantId, titleId, onHand, reserved, sellingPrice, lowStockThreshold, newActive,
                version, details);
    }

    public long available() {
        return onHand - reserved;
    }

    /** Ngưỡng của mặt hàng, nếu NULL thì dùng ngưỡng tenant. */
    public boolean isLowStock(int tenantThreshold) {
        int threshold = lowStockThreshold != null ? lowStockThreshold : tenantThreshold;
        return available() <= threshold;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID titleId() { return titleId; }
    public long onHand() { return onHand; }
    public long reserved() { return reserved; }
    public Money sellingPrice() { return sellingPrice; }
    public Integer lowStockThreshold() { return lowStockThreshold; }
    public boolean active() { return active; }
    public long version() { return version; }
    public StockItemDetails details() { return details; }
}
