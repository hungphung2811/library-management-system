package lms.sales.reporting.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lms.sales.reporting.application.SalesStatsQueryPort;
import lms.sales.reporting.domain.ReportRange;
import lms.sales.reporting.domain.RevenueTrend.DayTotal;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Truy vấn thống kê bán hàng (ADR-0018 mục 2.2). Native vì cần gom theo ngày giờ VN; bỏ qua bộ lọc @TenantId của
 * Hibernate nên MỌI câu đều có {@code tenant_id = :t} tường minh, cộng RLS. Khoảng thời gian lọc trên cột gốc bằng
 * mốc Instant (dùng chỉ mục V5), chỉ biểu thức gom nhóm mới đổi múi giờ. Hint timeout 2 giây.
 */
@Repository
class SalesStatsQueryAdapter implements SalesStatsQueryPort {

    private static final int TIMEOUT_MS = 2000;
    private static final String VN_DAY = "to_char(CAST(%s AT TIME ZONE 'Asia/Ho_Chi_Minh' AS date), 'YYYY-MM-DD')";

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public List<DayTotal> grossByDay(UUID tenantId, ReportRange range) {
        String day = VN_DAY.formatted("o.paid_at");
        Query q = timed(em.createNativeQuery("SELECT " + day + " AS d, COALESCE(SUM(o.grand_total), 0), COUNT(*) "
                + "FROM sales_orders o WHERE o.tenant_id = :t AND o.paid_at IS NOT NULL "
                + "AND o.paid_at >= :from AND o.paid_at < :to GROUP BY d ORDER BY d"));
        return days(q.setParameter("t", tenantId).setParameter("from", range.start())
                .setParameter("to", range.endExclusive()).getResultList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DayTotal> refundsByDay(UUID tenantId, ReportRange range) {
        String day = VN_DAY.formatted("r.refunded_at");
        Query q = timed(em.createNativeQuery("SELECT " + day + " AS d, COALESCE(SUM(r.amount), 0), COUNT(*) "
                + "FROM refunds r WHERE r.tenant_id = :t AND r.status = 'REFUNDED' "
                + "AND r.refunded_at >= :from AND r.refunded_at < :to GROUP BY d ORDER BY d"));
        return days(q.setParameter("t", tenantId).setParameter("from", range.start())
                .setParameter("to", range.endExclusive()).getResultList());
    }

    @Override
    @Transactional(readOnly = true)
    public long countPendingOnlineOrders(UUID tenantId) {
        Query q = timed(em.createNativeQuery("SELECT COUNT(*) FROM sales_orders o WHERE o.tenant_id = :t "
                + "AND o.channel = 'ONLINE' AND o.status = 'PENDING_CONFIRMATION'"));
        return ((Number) q.setParameter("t", tenantId).getSingleResult()).longValue();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SoldTitle> topSold(UUID tenantId, ReportRange range, int limit) {
        Query q = timed(em.createNativeQuery(
                "WITH sold AS (SELECT l.title_id, MAX(l.title_name) AS name, SUM(l.quantity) AS qty, "
                        + "SUM(l.line_total) AS rev FROM sales_order_lines l "
                        + "JOIN sales_orders o ON o.id = l.order_id AND o.tenant_id = l.tenant_id "
                        + "WHERE l.tenant_id = :t AND o.tenant_id = :t AND o.paid_at IS NOT NULL "
                        + "AND o.paid_at >= :from AND o.paid_at < :to GROUP BY l.title_id), "
                        + "ret AS (SELECT l.title_id, SUM(rl.quantity) AS qty, SUM(rl.refund_amount) AS rev "
                        + "FROM sales_return_lines rl "
                        + "JOIN sales_returns sr ON sr.id = rl.return_id AND sr.tenant_id = rl.tenant_id "
                        + "JOIN sales_order_lines l ON l.id = rl.order_line_id AND l.tenant_id = rl.tenant_id "
                        + "WHERE rl.tenant_id = :t AND sr.tenant_id = :t AND sr.created_at >= :from "
                        + "AND sr.created_at < :to GROUP BY l.title_id) "
                        + "SELECT s.title_id, s.name, s.qty - COALESCE(r.qty, 0) AS q, "
                        + "s.rev - COALESCE(r.rev, 0) AS v FROM sold s LEFT JOIN ret r ON r.title_id = s.title_id "
                        + "WHERE s.qty - COALESCE(r.qty, 0) > 0 ORDER BY q DESC, s.title_id ASC LIMIT :limit"));
        List<?> rows = q.setParameter("t", tenantId).setParameter("from", range.start())
                .setParameter("to", range.endExclusive()).setParameter("limit", limit).getResultList();
        return rows.stream().map(o -> (Object[]) o).map(r -> new SoldTitle((UUID) r[0], (String) r[1],
                ((Number) r[2]).longValue(), ((Number) r[3]).longValue())).toList();
    }

    private static Query timed(Query q) {
        return q.setHint("jakarta.persistence.query.timeout", TIMEOUT_MS);
    }

    private static List<DayTotal> days(List<?> rows) {
        return rows.stream().map(o -> (Object[]) o).map(r -> new DayTotal(LocalDate.parse((String) r[0]),
                ((Number) r[1]).longValue(), ((Number) r[2]).longValue())).toList();
    }
}
