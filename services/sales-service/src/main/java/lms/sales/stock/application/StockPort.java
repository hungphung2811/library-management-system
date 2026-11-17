package lms.sales.stock.application;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.stock.domain.StockItem;
import lms.sales.stock.domain.StockMovement;

/**
 * Cổng tồn kho. Bộ đếm chỉ đổi qua {@link #tryApply}: adapter JPA cài bằng MỘT câu UPDATE có điều kiện (JPQL bulk,
 * có tenant_id tường minh):
 * <pre>
 * UPDATE stock_items SET on_hand = on_hand + :dOn, reserved = reserved + :dRes
 *  WHERE tenant_id = :t AND title_id = :title
 *    AND on_hand + :dOn >= 0 AND reserved + :dRes >= 0 AND reserved + :dRes <= on_hand + :dOn
 * </pre>
 * trả true nếu đúng một dòng bị ảnh hưởng. Mọi lệnh gọi {@code tryApply} thành công phải đi kèm
 * {@link #append} trong cùng transaction.
 */
public interface StockPort {

    Optional<StockItem> find(UUID tenantId, UUID titleId);

    /** Đọc có khóa ghi (PESSIMISTIC_WRITE), dùng cho kiểm kho cần đọc rồi ghi. */
    Optional<StockItem> findForUpdate(UUID tenantId, UUID titleId);

    Map<UUID, StockItem> findByTitleIds(UUID tenantId, Collection<UUID> titleIds);

    /** Chèn mặt hàng mới; trùng (tenant, title) thì ném SalesException(STOCK_ITEM_EXISTS). */
    StockItem insert(StockItem item);

    /** Lưu các trường không phải bộ đếm: sellingPrice, lowStockThreshold, active. */
    StockItem saveAttributes(StockItem item);

    boolean tryApply(UUID tenantId, UUID titleId, long deltaOnHand, long deltaReserved);

    void append(StockMovement movement);

    PageResult<StockItem> list(UUID tenantId, boolean lowStockOnly, int tenantThreshold, int page, int size);

    /** Mặt hàng đang bán (active), mới nhất trước: danh mục công khai (storefront). */
    PageResult<StockItem> listActive(UUID tenantId, int page, int size);

    PageResult<StockMovement> ledger(UUID tenantId, UUID titleId, int page, int size);

    /** Σ delta của sổ cái theo mặt hàng, dùng đối soát. */
    LedgerTotals ledgerTotals(UUID tenantId, UUID titleId);

    record LedgerTotals(long onHand, long reserved) {
    }
}
