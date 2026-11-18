package lms.sales.settings.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lms.sales.settings.domain.OrderAbusePolicy;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.springframework.transaction.annotation.Transactional;

/** Đọc và đổi chính sách chống lạm dụng của tenant. Use case nghiệp vụ đặt hàng đọc qua {@link #current}. */
public class OrderAbusePolicyUseCases {

    private final OrderAbusePolicyPort port;
    private final OrderAbusePolicy.Values defaults;
    private final SalesAuditPort audit;
    private final Clock clock;

    public OrderAbusePolicyUseCases(OrderAbusePolicyPort port, OrderAbusePolicy.Values defaults,
                                    SalesAuditPort audit, Clock clock) {
        this.port = port;
        this.defaults = defaults;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OrderAbusePolicy get(UUID tenantId) {
        return port.find(tenantId).orElseGet(() -> OrderAbusePolicy.defaults(tenantId, defaults));
    }

    /** Giá trị đang áp dụng (đã lưu hoặc mặc định). */
    public OrderAbuseLimits current(UUID tenantId) {
        var v = get(tenantId).values();
        return new OrderAbuseLimits(v.reservationMinutes(), v.maxPendingOrders(), v.maxOrdersPerDay(),
                v.maxQuantityPerLine(), v.maxTotalQuantity(), v.ordersPerMinutePerUser(), v.ordersPerMinuteTenant());
    }

    @Transactional
    public OrderAbusePolicy update(UUID tenantId, UUID actor, OrderAbusePolicy.Values values, long expectedVersion) {
        OrderAbusePolicy current = port.find(tenantId).orElseGet(() -> OrderAbusePolicy.defaults(tenantId, defaults));
        if (current.version() != expectedVersion) {
            throw new SalesException(ErrorCode.SETTINGS_VERSION_CONFLICT, "Chính sách đã được người khác thay đổi");
        }
        var now = clock.instant();
        OrderAbusePolicy saved = port.save(current.update(values, now, actor));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policy", "ORDER_ABUSE");
        data.put("reservationMinutes", values.reservationMinutes());
        data.put("maxPendingOrders", values.maxPendingOrders());
        data.put("maxOrdersPerDay", values.maxOrdersPerDay());
        data.put("maxQuantityPerLine", values.maxQuantityPerLine());
        data.put("maxTotalQuantity", values.maxTotalQuantity());
        data.put("ordersPerMinutePerUser", values.ordersPerMinutePerUser());
        data.put("ordersPerMinuteTenant", values.ordersPerMinuteTenant());
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.SETTINGS_CHANGED,
                "OrderAbusePolicy", tenantId, actor, now, data));
        return saved;
    }
}
