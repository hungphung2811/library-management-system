package lms.sales.settings.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lms.sales.settings.application.OrderAbusePolicyPort;
import lms.sales.settings.domain.OrderAbusePolicy;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC như {@link SalesSettingsPersistenceAdapter} (khóa chính là tenant_id, chịu RLS, khóa lạc quan theo version). */
@Repository
class OrderAbusePolicyPersistenceAdapter implements OrderAbusePolicyPort {

    private static final String COLUMNS = "tenant_id, reservation_minutes, max_pending_orders, max_orders_per_day, "
            + "max_quantity_per_line, max_total_quantity, orders_per_minute_per_user, orders_per_minute_tenant, "
            + "version, updated_at, updated_by";

    private final JdbcTemplate jdbc;

    OrderAbusePolicyPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderAbusePolicy> find(UUID tenantId) {
        return jdbc.query("select " + COLUMNS + " from order_abuse_policies where tenant_id = ?", (rs, i) ->
                OrderAbusePolicy.restore(rs.getObject("tenant_id", UUID.class),
                        new OrderAbusePolicy.Values(rs.getInt("reservation_minutes"), rs.getInt("max_pending_orders"),
                                rs.getInt("max_orders_per_day"), rs.getInt("max_quantity_per_line"),
                                rs.getInt("max_total_quantity"), rs.getInt("orders_per_minute_per_user"),
                                rs.getInt("orders_per_minute_tenant")),
                        rs.getLong("version"), rs.getTimestamp("updated_at").toInstant(),
                        rs.getObject("updated_by", UUID.class)), tenantId).stream().findFirst();
    }

    @Override
    @Transactional
    public OrderAbusePolicy save(OrderAbusePolicy p) {
        var v = p.values();
        Timestamp at = Timestamp.from(p.updatedAt() == null ? Instant.now() : p.updatedAt());
        if (p.version() == 0) {
            try {
                jdbc.update("insert into order_abuse_policies (" + COLUMNS
                                + ") values (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                        p.tenantId(), v.reservationMinutes(), v.maxPendingOrders(), v.maxOrdersPerDay(),
                        v.maxQuantityPerLine(), v.maxTotalQuantity(), v.ordersPerMinutePerUser(),
                        v.ordersPerMinuteTenant(), at, p.updatedBy());
            } catch (DuplicateKeyException e) {
                throw conflict();
            }
        } else {
            int rows = jdbc.update("update order_abuse_policies set reservation_minutes = ?, max_pending_orders = ?, "
                            + "max_orders_per_day = ?, max_quantity_per_line = ?, max_total_quantity = ?, "
                            + "orders_per_minute_per_user = ?, orders_per_minute_tenant = ?, updated_at = ?, "
                            + "updated_by = ?, version = version + 1 where tenant_id = ? and version = ?",
                    v.reservationMinutes(), v.maxPendingOrders(), v.maxOrdersPerDay(), v.maxQuantityPerLine(),
                    v.maxTotalQuantity(), v.ordersPerMinutePerUser(), v.ordersPerMinuteTenant(), at, p.updatedBy(),
                    p.tenantId(), p.version());
            if (rows != 1) {
                throw conflict();
            }
        }
        return find(p.tenantId()).orElseThrow();
    }

    private static SalesException conflict() {
        return new SalesException(ErrorCode.SETTINGS_VERSION_CONFLICT, "Chính sách đã được người khác thay đổi");
    }
}
