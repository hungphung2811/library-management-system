package lms.sales.ordering.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.application.SalesAuditQueryPort;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi audit {@code PAYMENT_ANOMALY} trong transaction RIÊNG (ADR-0029 D5). Gọi sau khi transaction nghiệp vụ của
 * {@link ProcessPaymentWebhookUseCase} đã rollback, nên phải mở transaction mới (REQUIRES_NEW, cũng giữ độc lập nếu sau
 * này có người bọc transaction bên ngoài). Phải được gọi khi {@code TenantContext} đã gắn đúng tenant của bất thường
 * (request thăm dò, hoặc {@code callAs} của webhook): kết nối mới được đặt {@code app.tenant_id} theo đó nên RLS đúng.
 * Tối đa một dòng cho mỗi (lệnh, loại bất thường): đã có thì bỏ qua. Hai lần đồng thời có thể ra hai dòng (chấp nhận).
 * Dữ liệu audit không có PII (không tên, điện thoại, email, nội dung chuyển khoản).
 */
public class PaymentAnomalyAuditUseCase {

    private final SalesAuditPort audit;
    private final SalesAuditQueryPort auditQuery;
    private final Clock clock;

    public PaymentAnomalyAuditUseCase(SalesAuditPort audit, SalesAuditQueryPort auditQuery, Clock clock) {
        this.audit = audit;
        this.auditQuery = auditQuery;
        this.clock = clock;
    }

    /** @return true nếu đã ghi một dòng mới, false nếu đã có dòng cùng (lệnh, loại). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordOnce(PaymentAnomaly a) {
        if (auditQuery.hasPaymentAnomaly(a.tenantId(), a.intentId(), a.type().name())) {
            return false;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        put(data, "anomalyType", a.type().name());
        put(data, "source", a.source() == null ? null : a.source().name());
        put(data, "provider", a.provider());
        put(data, "paymentIntentId", a.intentId() == null ? null : a.intentId().toString());
        put(data, "orderId", a.orderId() == null ? null : a.orderId().toString());
        put(data, "reference", a.reference());
        put(data, "expectedAmount", a.expectedAmount());
        put(data, "receivedAmount", a.receivedAmount());
        put(data, "providerTxnId", a.providerTxnId());
        put(data, "intentStatus", a.intentStatus());
        put(data, "errorCode", a.errorCode());
        audit.record(new SalesAuditPort.AuditEntry(a.tenantId(), SalesAuditPort.Actions.PAYMENT_ANOMALY,
                "PaymentIntent", a.intentId(), null, clock.instant(), data));
        return true;
    }

    private static void put(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
