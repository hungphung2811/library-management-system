package lms.sales.reporting.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lms.sales.reporting.application.SalesOrderReportPort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đọc đơn cho báo cáo CSV (ADR-0018). Native có {@code tenant_id = :t} tường minh cộng RLS, timeout 20 giây. Chỉ chọn
 * cột tiền/trạng thái, KHÔNG chọn walk_in_*, recipient_*, customer_user_id. Điều kiện lọc ghép theo cờ, giá trị luôn
 * là bind parameter.
 */
@Repository
class SalesOrderReportQueryAdapter implements SalesOrderReportPort {

    private static final int TIMEOUT_MS = 20_000;

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public long count(UUID tenantId, Filter f) {
        Query q = em.createNativeQuery("SELECT COUNT(*) FROM sales_orders o WHERE " + where(f));
        return ((Number) bind(q, tenantId, f).getSingleResult()).longValue();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Row> page(UUID tenantId, Filter f, int offset, int limit) {
        Query q = em.createNativeQuery("SELECT o.order_no, o.channel, o.status, o.payment_status, o.created_at, "
                + "o.paid_at, COALESCE((SELECT SUM(l.quantity) FROM sales_order_lines l "
                + "WHERE l.order_id = o.id AND l.tenant_id = :t), 0), o.subtotal, o.discount_total, o.vat_total, "
                + "o.shipping_fee, o.grand_total FROM sales_orders o WHERE " + where(f)
                + " ORDER BY o.created_at DESC, o.id ASC LIMIT :limit OFFSET :offset");
        List<?> rows = bind(q, tenantId, f).setParameter("limit", limit).setParameter("offset", offset)
                .getResultList();
        return rows.stream().map(o -> (Object[]) o).map(r -> new Row((String) r[0], (String) r[1], (String) r[2],
                (String) r[3], instant(r[4]), instant(r[5]), num(r[6]), num(r[7]), num(r[8]), num(r[9]), num(r[10]),
                num(r[11]))).toList();
    }

    private static String where(Filter f) {
        return "o.tenant_id = :t AND o.created_at >= :from AND o.created_at < :to"
                + (f.channel() != null ? " AND o.channel = :channel" : "")
                + (f.status() != null ? " AND o.status = :status" : "");
    }

    private static Query bind(Query q, UUID tenantId, Filter f) {
        q.setHint("jakarta.persistence.query.timeout", TIMEOUT_MS).setParameter("t", tenantId)
                .setParameter("from", f.range().start()).setParameter("to", f.range().endExclusive());
        if (f.channel() != null) {
            q.setParameter("channel", f.channel());
        }
        if (f.status() != null) {
            q.setParameter("status", f.status());
        }
        return q;
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static Instant instant(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Instant i) {
            return i;
        }
        if (o instanceof OffsetDateTime t) {
            return t.toInstant();
        }
        if (o instanceof Timestamp t) {
            return t.toInstant();
        }
        if (o instanceof java.util.Date d) {
            return d.toInstant();
        }
        throw new IllegalStateException("Kiểu thời điểm không hỗ trợ: " + o.getClass());
    }
}
