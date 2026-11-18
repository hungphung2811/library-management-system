package lms.sales.stock.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.StockItem;
import lms.sales.stock.domain.StockItemDetails;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chi tiết quản lý của mặt hàng (SKU, nhà cung cấp, giá nhập, vị trí kho, số lượng đặt lại, lần nhập gần nhất, ghi chú
 * nội bộ): xem và thay thế toàn bộ. Dữ liệu nội bộ (giá nhập, ghi chú) chỉ đi qua các use case này, endpoint có khóa
 * quyền riêng. Audit chỉ ghi TÊN trường đã đổi, không ghi giá trị.
 */
public class StockItemDetailsUseCases {

    private final StockPort stock;
    private final SalesAuditPort audit;
    private final Clock clock;

    public StockItemDetailsUseCases(StockPort stock, SalesAuditPort audit, Clock clock) {
        this.stock = stock;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StockItem get(UUID tenantId, UUID titleId) {
        return load(tenantId, titleId);
    }

    /**
     * Thay toàn bộ chi tiết. {@code lastRestockedAt} null nghĩa là giữ nguyên giá trị hiện có (giá trị này còn được cập
     * nhật tự động khi xác nhận phiếu nhập); các trường khác null nghĩa là xóa.
     */
    @Transactional
    public StockItem update(UUID tenantId, UUID userId, UUID titleId, StockItemDetails requested) {
        StockItem item = load(tenantId, titleId);
        StockItemDetails current = item.details();
        StockItemDetails next = requested.lastRestockedAt() != null ? requested
                : requested.withLastRestockedAt(current.lastRestockedAt());
        requireSkuFree(tenantId, next.sku(), item.id());
        StockItem saved = stock.saveAttributes(item.withDetails(next));
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(current.sku(), next.sku())) changed.add("sku");
        if (!Objects.equals(current.supplier(), next.supplier())) changed.add("supplier");
        if (!Objects.equals(current.costPrice(), next.costPrice())) changed.add("costPrice");
        if (!Objects.equals(current.binLocation(), next.binLocation())) changed.add("binLocation");
        if (!Objects.equals(current.reorderQuantity(), next.reorderQuantity())) changed.add("reorderQuantity");
        if (!Objects.equals(current.lastRestockedAt(), next.lastRestockedAt())) changed.add("lastRestockedAt");
        if (!Objects.equals(current.note(), next.note())) changed.add("note");
        Map<String, Object> data = new HashMap<>();
        data.put("titleId", titleId.toString());
        data.put("changedFields", changed);
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.STOCK_ITEM_DETAILS_UPDATED,
                "StockItem", item.id(), userId, clock.instant(), data));
        return saved;
    }

    /** SKU (đã chuẩn hóa) chưa thuộc mặt hàng nào khác trong tenant; lỗi sớm trước khi chạm ràng buộc DB. */
    static void requireSkuFree(StockPort stock, UUID tenantId, String sku, UUID ownItemId) {
        if (sku == null) {
            return;
        }
        stock.findBySku(tenantId, sku).filter(other -> !other.id().equals(ownItemId)).ifPresent(other -> {
            throw new SalesException(ErrorCode.SKU_EXISTS, "SKU đã được dùng cho mặt hàng khác");
        });
    }

    private void requireSkuFree(UUID tenantId, String sku, UUID ownItemId) {
        requireSkuFree(stock, tenantId, sku, ownItemId);
    }

    private StockItem load(UUID tenantId, UUID titleId) {
        return stock.find(tenantId, titleId)
                .orElseThrow(() -> new SalesException(ErrorCode.STOCK_ITEM_NOT_FOUND, "Không có mặt hàng"));
    }
}
