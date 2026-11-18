package lms.sales.shift.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.shift.application.ShiftLedgerPort;
import lms.sales.shift.application.ShiftReport;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Báo cáo ca bằng 3 câu gom nhóm cố định (thu theo phương thức, hoàn theo phương thức, tổng đơn), không lặp theo đơn
 * (ADR-0030 D3.8); chạy trên các entity chỉ đọc cùng bảng với ordering, nên shift không phụ thuộc package ordering.
 */
@Repository
class ShiftLedgerAdapter implements ShiftLedgerPort {

    /** Thứ tự cố định các phương thức luôn có dòng trong báo cáo (ADR-0030 D3.8). */
    static final List<String> METHODS = List.of("CASH", "CARD", "BANK_TRANSFER", "QR", "MOMO", "SEPAY");

    /** Mốc "chưa đóng": không có giao dịch nào sau mốc này. */
    private static final Instant NEVER = Instant.parse("9999-12-31T00:00:00Z");

    /** Chỉ số trong mảng cộng dồn theo phương thức. */
    private static final int PAY_COUNT = 0;
    private static final int PAY_SUM = 1;
    private static final int REFUND_COUNT = 2;
    private static final int REFUND_SUM = 3;
    private static final int PAY_AFTER_COUNT = 4;
    private static final int PAY_AFTER_SUM = 5;
    private static final int REFUND_AFTER_COUNT = 6;
    private static final int REFUND_AFTER_SUM = 7;

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public ShiftReport report(UUID tenantId, UUID shiftId, Instant closedAt) {
        Instant mark = closedAt == null ? NEVER : closedAt;
        Map<String, long[]> byMethod = new LinkedHashMap<>();
        METHODS.forEach(m -> byMethod.put(m, new long[8]));

        for (Object[] r : em.createQuery("select p.method, count(p), sum(p.amount), "
                        + "sum(case when p.recordedAt > :mark then 1 else 0 end), "
                        + "sum(case when p.recordedAt > :mark then p.amount else 0 end) "
                        + "from ShiftPaymentJpaEntity p where p.tenantId = :t and p.shiftId = :s group by p.method",
                Object[].class).setParameter("t", tenantId).setParameter("s", shiftId).setParameter("mark", mark)
                .getResultList()) {
            long[] a = byMethod.computeIfAbsent((String) r[0], k -> new long[8]);
            a[PAY_COUNT] = num(r[1]);
            a[PAY_SUM] = num(r[2]);
            a[PAY_AFTER_COUNT] = num(r[3]);
            a[PAY_AFTER_SUM] = num(r[4]);
        }
        for (Object[] r : em.createQuery("select r.method, count(r), sum(r.amount), "
                        + "sum(case when r.refundedAt > :mark then 1 else 0 end), "
                        + "sum(case when r.refundedAt > :mark then r.amount else 0 end) "
                        + "from ShiftRefundJpaEntity r where r.tenantId = :t and r.shiftId = :s "
                        + "and r.status = 'REFUNDED' group by r.method", Object[].class)
                .setParameter("t", tenantId).setParameter("s", shiftId).setParameter("mark", mark)
                .getResultList()) {
            long[] a = byMethod.computeIfAbsent((String) r[0], k -> new long[8]);
            a[REFUND_COUNT] = num(r[1]);
            a[REFUND_SUM] = num(r[2]);
            a[REFUND_AFTER_COUNT] = num(r[3]);
            a[REFUND_AFTER_SUM] = num(r[4]);
        }

        Object[] orders = em.createQuery("select count(o), coalesce(sum(o.grandTotal), 0), "
                        + "coalesce(sum(o.discountTotal), 0) from ShiftOrderJpaEntity o where o.tenantId = :t "
                        + "and o.id in (select p.orderId from ShiftPaymentJpaEntity p where p.tenantId = :t "
                        + "and p.shiftId = :s)", Object[].class)
                .setParameter("t", tenantId).setParameter("s", shiftId).getSingleResult();

        List<ShiftReport.MethodLine> lines = new ArrayList<>();
        long collected = 0;
        long refunded = 0;
        for (var e : byMethod.entrySet()) {
            long[] a = e.getValue();
            lines.add(new ShiftReport.MethodLine(e.getKey(), a[PAY_COUNT], a[PAY_SUM], a[REFUND_COUNT],
                    a[REFUND_SUM], a[PAY_SUM] - a[REFUND_SUM], a[PAY_AFTER_COUNT], a[PAY_AFTER_SUM],
                    a[REFUND_AFTER_COUNT], a[REFUND_AFTER_SUM]));
            collected += a[PAY_SUM];
            refunded += a[REFUND_SUM];
        }
        return new ShiftReport(num(orders[0]), num(orders[1]), num(orders[2]), lines, collected, refunded,
                collected - refunded);
    }

    @Override
    @Transactional(readOnly = true)
    public long pendingManualIntents(UUID tenantId, UUID shiftId, Instant now) {
        return em.createQuery("select count(i) from ShiftIntentJpaEntity i where i.tenantId = :t "
                        + "and i.shiftId = :s and i.status = 'PENDING' and i.provider = 'MANUAL' "
                        + "and i.expiresAt > :now", Long.class)
                .setParameter("t", tenantId).setParameter("s", shiftId).setParameter("now", now).getSingleResult();
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
