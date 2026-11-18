package lms.sales.stock.application;

import java.util.Collection;
import java.util.List;
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

    /** Lưu các trường không phải bộ đếm: sellingPrice, lowStockThreshold, active và thông tin quản lý (details). */
    StockItem saveAttributes(StockItem item);

    /**
     * Đặt lastRestockedAt = {@code at} cho các mặt hàng vừa có phiếu nhập RECEIPT (chỉ khi mới hơn giá trị đang có).
     * Chạy trong transaction của caller. Mặc định không làm gì (bản giả test không theo dõi trường này).
     */
    default void touchLastRestocked(UUID tenantId, Collection<UUID> titleIds, java.time.Instant at) {
    }

    /** Mặt hàng theo SKU đã chuẩn hóa (chữ hoa), dùng kiểm trùng SKU trước khi ghi. */
    default Optional<StockItem> findBySku(UUID tenantId, String sku) {
        return Optional.empty();
    }

    boolean tryApply(UUID tenantId, UUID titleId, long deltaOnHand, long deltaReserved);

    void append(StockMovement movement);

    PageResult<StockItem> list(UUID tenantId, boolean lowStockOnly, int tenantThreshold, int page, int size);

    /**
     * Như {@link #list} (cùng định nghĩa tồn thấp) nhưng sắp theo available tăng dần rồi titleId, ổn định để phân
     * trang báo cáo (ADR-0018). Mặc định chuyển sang {@link #list} cho bản giả test.
     */
    default PageResult<StockItem> listForReport(UUID tenantId, boolean lowStockOnly, int tenantThreshold, int page,
                                                int size) {
        return list(tenantId, lowStockOnly, tenantThreshold, page, size);
    }

    /**
     * Mặt hàng đang bán (active) cho storefront, phân trang ở DB (ADR-0019). {@code NEWEST}: theo ngày đưa vào bán;
     * {@code PRICE_*}: theo giá hiệu lực của DB (selling_price, nếu không có thì bản sao giá bìa), loại mặt hàng chưa có
     * giá hiệu lực; thứ tự ổn định theo id. {@code inStockOnly}: chỉ available > 0.
     */
    PageResult<StorefrontRow> listStorefront(UUID tenantId, StorefrontSort sort, boolean inStockOnly, int page,
                                             int size);

    /** Dòng storefront của các đầu sách cho trước; chỉ mặt hàng active (không thấy thì không có trong map). */
    Map<UUID, StorefrontRow> findStorefrontRows(UUID tenantId, Collection<UUID> titleIds);

    /** Tối đa {@code limit} đầu sách active chưa có giá hiệu lực ở DB (không selling_price, chưa có bản sao giá bìa). */
    List<UUID> titleIdsMissingCoverPrice(UUID tenantId, int limit);

    /**
     * Ghi bản sao giá bìa (đồng, đã làm tròn) cho mặt hàng, chỉ khi khác giá trị đang có. Ghi trong transaction riêng
     * (hoặc cùng transaction của caller nếu có); không bao giờ do khách điều khiển.
     */
    void refreshCoverPrices(UUID tenantId, Map<UUID, Long> pricesByTitle);

    PageResult<StockMovement> ledger(UUID tenantId, UUID titleId, int page, int size);

    /** Σ delta của sổ cái theo mặt hàng, dùng đối soát. */
    LedgerTotals ledgerTotals(UUID tenantId, UUID titleId);

    record LedgerTotals(long onHand, long reserved) {
    }
}
