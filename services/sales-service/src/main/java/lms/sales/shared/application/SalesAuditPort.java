package lms.sales.shared.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Ghi sales_audit_log trong cùng transaction với thao tác nghiệp vụ (ADR-0010 mục 2.6).
 * {@code data} chỉ chứa id, số tiền, số lượng, mã trạng thái: không tên/điện thoại/địa chỉ.
 */
public interface SalesAuditPort {

    void record(AuditEntry entry);

    record AuditEntry(UUID tenantId, String action, String aggregateType, UUID aggregateId, UUID actorId,
                      Instant occurredAt, Map<String, Object> data) {
        public AuditEntry {
            data = Map.copyOf(data);
        }
    }

    /** Tên hành động audit (pha 1 và pha 2). */
    interface Actions {
        String ORDER_CREATED = "ORDER_CREATED";
        String ORDER_DISCOUNT_APPLIED = "ORDER_DISCOUNT_APPLIED";
        String ORDER_PAID = "ORDER_PAID";
        String ORDER_CONFIRMED = "ORDER_CONFIRMED";
        String ORDER_READY_FOR_PICKUP = "ORDER_READY_FOR_PICKUP";
        String ORDER_SHIPPING = "ORDER_SHIPPING";
        String ORDER_COMPLETED = "ORDER_COMPLETED";
        String ORDER_CANCELLED = "ORDER_CANCELLED";
        String RETURN_CREATED = "RETURN_CREATED";
        String RETURN_LATE_APPROVED = "RETURN_LATE_APPROVED";
        String REFUND_CREATED = "REFUND_CREATED";
        String REFUND_COMPLETED = "REFUND_COMPLETED";
        String WALK_IN_PII_ANONYMIZED = "WALK_IN_PII_ANONYMIZED";
        String GOODS_RECEIPT_CREATED = "GOODS_RECEIPT_CREATED";
        String GOODS_RECEIPT_CONFIRMED = "GOODS_RECEIPT_CONFIRMED";
        String GOODS_RECEIPT_CANCELLED = "GOODS_RECEIPT_CANCELLED";
        String STOCK_ADJUSTED = "STOCK_ADJUSTED";
        String STOCKTAKE_RECORDED = "STOCKTAKE_RECORDED";
        String STOCK_ITEM_REGISTERED = "STOCK_ITEM_REGISTERED";
        String PRICE_CHANGED = "PRICE_CHANGED";
        String STOCK_ITEM_DETAILS_UPDATED = "STOCK_ITEM_DETAILS_UPDATED";
        String ORDER_NOTES_UPDATED = "ORDER_NOTES_UPDATED";
        String SETTINGS_CHANGED = "SETTINGS_CHANGED";
        String SUPPLIER_CREATED = "SUPPLIER_CREATED";
        String SUPPLIER_UPDATED = "SUPPLIER_UPDATED";
        String SHOP_CONTACT_CREATED = "SHOP_CONTACT_CREATED";
        String SHOP_CONTACT_STATUS_CHANGED = "SHOP_CONTACT_STATUS_CHANGED";
        String SHOP_CONTACT_ANONYMIZED = "SHOP_CONTACT_ANONYMIZED";
        String SHOP_CONTACT_PII_ANONYMIZED = "SHOP_CONTACT_PII_ANONYMIZED";
        String BANNER_CREATED = "BANNER_CREATED";
        String BANNER_UPDATED = "BANNER_UPDATED";
        String BANNER_DELETED = "BANNER_DELETED";
    }
}
