package lms.sales.stock.domain;

import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.domain.SalesException;

/**
 * Dòng sổ cái tồn kho (chỉ chèn thêm). Đối soát: onHand = Σ deltaOnHand, reserved = Σ deltaReserved theo mặt hàng.
 * Dấu của delta được ràng buộc theo loại để bắt lỗi lập trình.
 */
public record StockMovement(UUID id, UUID tenantId, UUID titleId, MovementType type, long deltaOnHand,
                            long deltaReserved, String refType, UUID refId, String reason, UUID actorId,
                            Instant occurredAt) {

    public StockMovement {
        if (type.requiresReason() && (reason == null || reason.isBlank())) {
            throw SalesException.reasonRequired();
        }
        boolean ok = switch (type) {
            case RECEIPT, RETURN, CANCEL_RESTOCK -> deltaOnHand > 0 && deltaReserved == 0;
            case SALE, WRITE_OFF -> deltaOnHand < 0 && deltaReserved == 0;
            case RESERVE -> deltaOnHand == 0 && deltaReserved > 0;
            case RELEASE -> deltaOnHand == 0 && deltaReserved < 0;
            case FULFIL -> deltaOnHand < 0 && deltaReserved == deltaOnHand;
            case ADJUSTMENT -> deltaOnHand != 0 && deltaReserved == 0;
            case STOCKTAKE -> deltaReserved == 0;
        };
        if (!ok) {
            throw new IllegalArgumentException("Delta không hợp lệ cho loại " + type);
        }
    }

    public static StockMovement of(UUID tenantId, UUID titleId, MovementType type, long deltaOnHand,
                                   long deltaReserved, String refType, UUID refId, String reason, UUID actorId,
                                   Instant at) {
        return new StockMovement(UUID.randomUUID(), tenantId, titleId, type, deltaOnHand, deltaReserved, refType,
                refId, reason, actorId, at);
    }
}
