package lms.sales.ordering.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.application.SalesEventEnrichmentPort;
import org.springframework.stereotype.Component;

/**
 * Làm giàu sự kiện đơn hàng, hoàn tiền, hóa đơn cho thông báo email (ADR-0030 gói D). Đọc đơn bằng {@code em.find}
 * theo khóa chính: đơn vừa được lưu/đọc trong cùng transaction nên đã nằm trong persistence context, không phát sinh
 * thêm SQL (không dùng truy vấn JPQL theo id vì mỗi lần đều đi xuống DB). Chỉ thêm id, mã đơn, kênh, hình thức nhận, số
 * tiền, mã loại hủy: không bao giờ có tên, điện thoại, địa chỉ, email.
 *
 * <p>Trường thêm (vắng mặt khi không có): {@code customerUserId} (khách lẻ/POS không tài khoản thì vắng),
 * {@code orderNo}, {@code channel} (POS|ONLINE), {@code fulfilment}, {@code grandTotal} (đơn), {@code cancelKind}
 * (ORDER_CANCELLED: CUSTOMER|STAFF|EXPIRED|PAYMENT_TIMEOUT), {@code issuedAt} (INVOICE_ISSUED).
 */
@Component
class OrderEventEnricher implements SalesEventEnrichmentPort {

    private static final Set<String> AGGREGATES = Set.of("SalesOrder", "Refund", "Invoice");

    @PersistenceContext
    private EntityManager em;

    @Override
    public Map<String, Object> enrich(SalesAuditPort.AuditEntry entry) {
        if (!AGGREGATES.contains(entry.aggregateType())) {
            return Map.of();
        }
        UUID orderId = orderIdOf(entry);
        if (orderId == null) {
            return Map.of();
        }
        SalesOrderJpaEntity order = em.find(SalesOrderJpaEntity.class, orderId);
        if (order == null) {
            return Map.of();
        }
        return build(entry, new OrderFacts(order.getCustomerUserId(), order.getOrderNo(), order.getChannel(),
                order.getFulfilment(), order.getGrandTotal()));
    }

    /** Các trường của đơn cần cho sự kiện (không có dữ liệu cá nhân). */
    record OrderFacts(UUID customerUserId, String orderNo, String channel, String fulfilment, long grandTotal) {
    }

    static Map<String, Object> build(SalesAuditPort.AuditEntry entry, OrderFacts o) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (o.customerUserId() != null) {
            extra.put("customerUserId", o.customerUserId().toString());
        }
        extra.put("orderNo", o.orderNo());
        extra.put("channel", o.channel());
        extra.put("fulfilment", o.fulfilment());
        if ("SalesOrder".equals(entry.aggregateType())) {
            extra.put("grandTotal", o.grandTotal());
            if (SalesAuditPort.Actions.ORDER_CANCELLED.equals(entry.action())) {
                extra.put("cancelKind", cancelKind(entry, o.customerUserId()));
            }
        }
        if ("Invoice".equals(entry.aggregateType()) && SalesAuditPort.Actions.INVOICE_ISSUED.equals(entry.action())
                && entry.occurredAt() != null) {
            extra.put("issuedAt", entry.occurredAt().toString());
        }
        return extra;
    }

    /**
     * Phân loại hủy từ chính dữ liệu audit đang có (không truy vấn thêm): cờ {@code byCustomer} của luồng hủy đơn,
     * {@code paymentIntent} của luồng hủy lệnh thanh toán, người thao tác (null = job nền).
     */
    static String cancelKind(SalesAuditPort.AuditEntry entry, UUID customerUserId) {
        Map<String, Object> d = entry.data();
        if (Boolean.TRUE.equals(d.get("byCustomer"))) {
            return "CUSTOMER";
        }
        if ("EXPIRED".equals(d.get("paymentIntent"))) {
            return "PAYMENT_TIMEOUT";
        }
        if (entry.actorId() == null) {
            return "EXPIRED"; // job hết hạn giữ hàng
        }
        if (entry.actorId().equals(customerUserId)) {
            return "CUSTOMER"; // khách tự hủy lệnh thanh toán đang chờ
        }
        return "STAFF";
    }

    private static UUID orderIdOf(SalesAuditPort.AuditEntry entry) {
        if ("SalesOrder".equals(entry.aggregateType())) {
            return entry.aggregateId();
        }
        Object id = entry.data().get("orderId");
        if (id == null) {
            return null;
        }
        try {
            return UUID.fromString(id.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
