package lms.sales.stock.application;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.MovementType;
import lms.sales.stock.domain.StockItem;
import lms.sales.stock.domain.StockMovement;

/**
 * API tồn kho cho các use case khác (ordering, nhập kho, điều chỉnh). Không tự mở transaction: chạy trong
 * transaction của use case gọi, nên lỗi giữa chừng rollback cả bộ đếm lẫn sổ cái.
 * Dòng trùng titleId được gộp, rồi xử lý theo titleId TĂNG DẦN (tránh khóa chéo giữa hai đơn nhiều dòng).
 */
public class StockService {

    private final StockPort stock;
    private final Clock clock;

    public StockService(StockPort stock, Clock clock) {
        this.stock = stock;
        this.clock = clock;
    }

    /** Mặt hàng đã đưa vào bán theo titleId (id chưa đăng ký thì không có trong map). */
    public Map<UUID, SellableItem> sellableItems(UUID tenantId, Collection<UUID> titleIds) {
        Map<UUID, SellableItem> out = new java.util.HashMap<>();
        stock.findByTitleIds(tenantId, titleIds).forEach((id, i) ->
                out.put(id, new SellableItem(id, i.active(), i.sellingPrice(), i.available())));
        return out;
    }

    /** Bán POS: onHand -= q nếu available >= q. Hết hàng: INSUFFICIENT_STOCK, không dòng nào bị ghi. */
    public void sellAll(UUID tenantId, UUID actorId, Collection<StockLine> lines, String refType, UUID refId) {
        Map<UUID, Long> merged = merge(lines);
        precheck(tenantId, merged);
        for (var e : merged.entrySet()) {
            applyOne(tenantId, actorId, e.getKey(), MovementType.SALE, -e.getValue(), 0, refType, refId, null);
        }
    }

    /** Giữ hàng cho đơn online: reserved += q nếu available >= q. */
    public void reserveAll(UUID tenantId, UUID actorId, Collection<StockLine> lines, String refType, UUID refId) {
        Map<UUID, Long> merged = merge(lines);
        precheck(tenantId, merged);
        for (var e : merged.entrySet()) {
            applyOne(tenantId, actorId, e.getKey(), MovementType.RESERVE, 0, e.getValue(), refType, refId, null);
        }
    }

    public void releaseAll(UUID tenantId, UUID actorId, Collection<StockLine> lines, String refType, UUID refId) {
        for (var e : merge(lines).entrySet()) {
            applyOne(tenantId, actorId, e.getKey(), MovementType.RELEASE, 0, -e.getValue(), refType, refId, null);
        }
    }

    /** Giữ -> đã giao: onHand -= q, reserved -= q. */
    public void fulfilAll(UUID tenantId, UUID actorId, Collection<StockLine> lines, String refType, UUID refId) {
        for (var e : merge(lines).entrySet()) {
            applyOne(tenantId, actorId, e.getKey(), MovementType.FULFIL, -e.getValue(), -e.getValue(), refType,
                    refId, null);
        }
    }

    /** Giao thất bại: hàng đã rời kho quay lại, onHand += q. */
    public void cancelRestockAll(UUID tenantId, UUID actorId, Collection<StockLine> lines, String refType,
                                 UUID refId) {
        for (var e : merge(lines).entrySet()) {
            applyOne(tenantId, actorId, e.getKey(), MovementType.CANCEL_RESTOCK, e.getValue(), 0, refType, refId,
                    null);
        }
    }

    /** Trả hàng nhập lại tồn: RETURN, onHand += q. */
    public void returnToStockAll(UUID tenantId, UUID actorId, Collection<StockLine> lines, String refType,
                                 UUID refId) {
        for (var e : merge(lines).entrySet()) {
            applyOne(tenantId, actorId, e.getKey(), MovementType.RETURN, e.getValue(), 0, refType, refId, null);
        }
    }

    /**
     * Trả hàng hỏng ghi hao hụt: RETURN +q rồi WRITE_OFF -q (tồn không đổi, sổ cái thấy rõ hao hụt), bắt buộc lý do.
     */
    public void returnAndWriteOffAll(UUID tenantId, UUID actorId, Collection<StockLine> lines, String refType,
                                     UUID refId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw SalesException.reasonRequired();
        }
        for (var e : merge(lines).entrySet()) {
            applyOne(tenantId, actorId, e.getKey(), MovementType.RETURN, e.getValue(), 0, refType, refId, null);
            applyOne(tenantId, actorId, e.getKey(), MovementType.WRITE_OFF, -e.getValue(), 0, refType, refId, reason);
        }
    }

    /** Nhập kho: onHand += q; mặt hàng chưa đưa vào bán thì STOCK_ITEM_NOT_REGISTERED. */
    public void receiveAll(UUID tenantId, UUID actorId, Collection<StockLine> lines, String refType, UUID refId) {
        for (var e : merge(lines).entrySet()) {
            applyOne(tenantId, actorId, e.getKey(), MovementType.RECEIPT, e.getValue(), 0, refType, refId, null);
        }
    }

    /** Điều chỉnh tay (delta khác 0), bắt buộc lý do. */
    public void adjust(UUID tenantId, UUID actorId, UUID titleId, long delta, String reason) {
        if (delta == 0) {
            throw SalesException.invalidQuantity("Chênh lệch điều chỉnh phải khác 0");
        }
        applyOne(tenantId, actorId, titleId, MovementType.ADJUSTMENT, delta, 0, "ADJUSTMENT", null, reason);
    }

    /** Ghi biến động đã tính sẵn (dùng cho kiểm kho, delta có thể bằng 0). */
    public void applyStocktake(UUID tenantId, UUID actorId, UUID titleId, long delta, String reason) {
        applyOne(tenantId, actorId, titleId, MovementType.STOCKTAKE, delta, 0, "STOCKTAKE", null, reason);
    }

    private void applyOne(UUID tenantId, UUID actorId, UUID titleId, MovementType type, long dOn, long dRes,
                          String refType, UUID refId, String reason) {
        // dựng movement trước để validate (lý do, dấu delta) khi chưa đụng DB
        StockMovement movement = StockMovement.of(tenantId, titleId, type, dOn, dRes, refType, refId, reason,
                actorId, clock.instant());
        if (!(dOn == 0 && dRes == 0) && !stock.tryApply(tenantId, titleId, dOn, dRes)) {
            StockItem item = stock.find(tenantId, titleId).orElseThrow(() -> SalesException.notRegistered(titleId));
            throw SalesException.insufficientStock(titleId, item.available());
        }
        if (dOn == 0 && dRes == 0 && stock.find(tenantId, titleId).isEmpty()) {
            throw SalesException.notRegistered(titleId);
        }
        stock.append(movement);
    }

    /** Kiểm sớm để báo lỗi chính xác trước khi ghi; vẫn có điều kiện của tryApply là lớp chặn cuối. */
    private void precheck(UUID tenantId, Map<UUID, Long> merged) {
        Map<UUID, StockItem> items = stock.findByTitleIds(tenantId, merged.keySet());
        for (var e : merged.entrySet()) {
            StockItem item = items.get(e.getKey());
            if (item == null) {
                throw SalesException.notRegistered(e.getKey());
            }
            if (item.available() < e.getValue()) {
                throw SalesException.insufficientStock(e.getKey(), item.available());
            }
        }
    }

    private static Map<UUID, Long> merge(Collection<StockLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw SalesException.invalidRequest("Cần ít nhất một dòng");
        }
        Map<UUID, Long> merged = new TreeMap<>(); // UUID so sánh theo thứ tự tăng dần ổn định
        for (StockLine l : List.copyOf(lines)) {
            merged.merge(l.titleId(), (long) l.quantity(), Long::sum);
        }
        return merged;
    }
}
