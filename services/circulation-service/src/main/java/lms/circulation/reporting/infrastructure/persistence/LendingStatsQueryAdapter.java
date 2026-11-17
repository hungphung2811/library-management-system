package lms.circulation.reporting.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.circulation.reporting.application.LendingStatsPort;
import lms.circulation.reporting.domain.DateRange;
import lms.circulation.reporting.domain.LendingCounts;
import lms.circulation.reporting.domain.OverdueCopy;
import lms.circulation.reporting.domain.TopTitle;
import lms.circulation.reporting.domain.UnpaidFines;
import lms.tenant.TenantContext;

/**
 * Adapter đọc thống kê mượn trả bằng truy vấn native (ADR-0018 mục 2.2): đã rà và được liệt kê trong
 * {@code noUnreviewedNativeQueries}. Native bỏ qua {@code @TenantId} của Hibernate nên MỌI truy vấn có
 * {@code tenant_id = :t} tường minh (lấy từ {@link TenantContext#require()}) cộng RLS của PostgreSQL; bảng
 * borrowing_items nối theo (borrowing_id, tenant_id). Tham số luôn là bind parameter; ngày so trực tiếp với cột DATE.
 * Truy vấn thống kê có timeout 2 giây, truy vấn báo cáo 20 giây (hint {@code jakarta.persistence.query.timeout}, ms).
 */
public class LendingStatsQueryAdapter implements LendingStatsPort {

    static final String TIMEOUT_HINT = "jakarta.persistence.query.timeout";
    static final int STATS_TIMEOUT_MS = 2000;
    static final int REPORT_TIMEOUT_MS = 20000;

    static final String COUNTS_SQL = """
            SELECT
              (SELECT count(DISTINCT b.id) FROM borrowings b
                 JOIN borrowing_items i ON i.borrowing_id = b.id AND i.tenant_id = b.tenant_id
                WHERE b.tenant_id = :t AND i.return_date IS NULL),
              (SELECT count(DISTINCT b.id) FROM borrowings b
                 JOIN borrowing_items i ON i.borrowing_id = b.id AND i.tenant_id = b.tenant_id
                WHERE b.tenant_id = :t AND i.return_date IS NULL AND b.due_date < :today),
              (SELECT count(*) FROM borrowings b
                 JOIN borrowing_items i ON i.borrowing_id = b.id AND i.tenant_id = b.tenant_id
                WHERE b.tenant_id = :t AND i.return_date IS NULL AND b.due_date < :today),
              (SELECT count(*) FROM borrowings b
                 JOIN borrowing_items i ON i.borrowing_id = b.id AND i.tenant_id = b.tenant_id
                WHERE b.tenant_id = :t AND b.borrow_date = :today),
              (SELECT count(*) FROM borrowing_items i
                WHERE i.tenant_id = :t AND i.return_date = :today AND i.item_condition IN ('GOOD', 'DAMAGED'))
            """;

    static final String FINES_SQL = """
            SELECT count(*), COALESCE(sum(f.amount), 0) FROM fines f
             WHERE f.tenant_id = :t AND f.status = 'UNPAID'
            """;

    static final String BORROWED_PER_DAY_SQL = """
            SELECT to_char(b.borrow_date, 'YYYY-MM-DD'), count(*) FROM borrowings b
              JOIN borrowing_items i ON i.borrowing_id = b.id AND i.tenant_id = b.tenant_id
             WHERE b.tenant_id = :t AND b.borrow_date >= :from AND b.borrow_date <= :to
             GROUP BY b.borrow_date
            """;

    static final String RETURNED_PER_DAY_SQL = """
            SELECT to_char(i.return_date, 'YYYY-MM-DD'), count(*) FROM borrowing_items i
             WHERE i.tenant_id = :t AND i.return_date >= :from AND i.return_date <= :to
               AND i.item_condition IN ('GOOD', 'DAMAGED')
             GROUP BY i.return_date
            """;

    static final String TOP_TITLES_SQL = """
            SELECT i.title_id, count(*) AS cnt FROM borrowings b
              JOIN borrowing_items i ON i.borrowing_id = b.id AND i.tenant_id = b.tenant_id
             WHERE b.tenant_id = :t AND b.borrow_date >= :from AND b.borrow_date <= :to AND i.title_id IS NOT NULL
             GROUP BY i.title_id
             ORDER BY cnt DESC, i.title_id ASC
             LIMIT :limit
            """;

    static final String COUNT_OVERDUE_COPIES_SQL = """
            SELECT count(*) FROM borrowings b
              JOIN borrowing_items i ON i.borrowing_id = b.id AND i.tenant_id = b.tenant_id
             WHERE b.tenant_id = :t AND i.return_date IS NULL AND b.due_date < :today
            """;

    static final String OVERDUE_COPIES_SQL = """
            SELECT b.id, b.user_id, i.book_copy_id, to_char(b.borrow_date, 'YYYY-MM-DD'), to_char(b.due_date, 'YYYY-MM-DD')
              FROM borrowings b
              JOIN borrowing_items i ON i.borrowing_id = b.id AND i.tenant_id = b.tenant_id
             WHERE b.tenant_id = :t AND i.return_date IS NULL AND b.due_date < :today
             ORDER BY b.due_date ASC, b.id ASC, i.book_copy_id ASC
             LIMIT :limit OFFSET :offset
            """;

    private final EntityManager em;

    public LendingStatsQueryAdapter(EntityManager em) {
        this.em = em;
    }

    @Override
    public LendingCounts counts(LocalDate today) {
        Object[] r = (Object[]) query(COUNTS_SQL, STATS_TIMEOUT_MS).setParameter("today", today).getSingleResult();
        return new LendingCounts(num(r[0]), num(r[1]), num(r[2]), num(r[3]), num(r[4]));
    }

    @Override
    public UnpaidFines unpaidFines() {
        Object[] r = (Object[]) query(FINES_SQL, STATS_TIMEOUT_MS).getSingleResult();
        return new UnpaidFines(num(r[0]), r[1] instanceof BigDecimal d ? d : new BigDecimal(r[1].toString()));
    }

    @Override
    public Map<LocalDate, Long> borrowedPerDay(DateRange range) {
        return perDay(BORROWED_PER_DAY_SQL, range);
    }

    @Override
    public Map<LocalDate, Long> returnedPerDay(DateRange range) {
        return perDay(RETURNED_PER_DAY_SQL, range);
    }

    private Map<LocalDate, Long> perDay(String sql, DateRange range) {
        Query q = query(sql, STATS_TIMEOUT_MS).setParameter("from", range.from()).setParameter("to", range.to());
        Map<LocalDate, Long> result = new LinkedHashMap<>();
        for (Object row : q.getResultList()) {
            Object[] r = (Object[]) row;
            result.put(LocalDate.parse((String) r[0]), num(r[1]));
        }
        return result;
    }

    @Override
    public List<TopTitle> topBorrowedTitles(DateRange range, int limit) {
        Query q = query(TOP_TITLES_SQL, STATS_TIMEOUT_MS)
                .setParameter("from", range.from()).setParameter("to", range.to()).setParameter("limit", limit);
        List<TopTitle> result = new ArrayList<>();
        for (Object row : q.getResultList()) {
            Object[] r = (Object[]) row;
            result.add(new TopTitle(uuid(r[0]), num(r[1])));
        }
        return result;
    }

    @Override
    public long countOverdueCopies(LocalDate today) {
        return num(query(COUNT_OVERDUE_COPIES_SQL, REPORT_TIMEOUT_MS).setParameter("today", today).getSingleResult());
    }

    @Override
    public List<OverdueCopy> overdueCopies(LocalDate today, int offset, int limit) {
        Query q = query(OVERDUE_COPIES_SQL, REPORT_TIMEOUT_MS)
                .setParameter("today", today).setParameter("limit", limit).setParameter("offset", offset);
        List<OverdueCopy> result = new ArrayList<>();
        for (Object row : q.getResultList()) {
            Object[] r = (Object[]) row;
            result.add(new OverdueCopy(uuid(r[0]), uuid(r[1]), uuid(r[2]),
                    LocalDate.parse((String) r[3]), LocalDate.parse((String) r[4])));
        }
        return result;
    }

    /** Native query đã gắn tenant của phiên (tường minh) và timeout. */
    private Query query(String sql, int timeoutMs) {
        return em.createNativeQuery(sql)
                .setParameter("t", TenantContext.require())
                .setHint(TIMEOUT_HINT, timeoutMs);
    }

    private static long num(Object value) {
        return ((Number) value).longValue();
    }

    private static UUID uuid(Object value) {
        return value instanceof UUID u ? u : UUID.fromString(value.toString());
    }
}
