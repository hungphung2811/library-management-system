package lms.sales.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lms.sales.reporting.application.SalesOrderReportPort;
import lms.sales.reporting.application.SalesStatsQueryPort;
import lms.sales.reporting.domain.ReportRange;
import lms.sales.reporting.domain.RevenueTrend.DayTotal;

/** Bản giả trong bộ nhớ của hai cổng đọc, lọc/gom nhóm theo đúng quy tắc của câu SQL (mốc Instant, ngày giờ VN). */
public final class ReportingFakes {

    private ReportingFakes() {
    }

    /** Đơn đã thanh toán (kể cả đơn sau đó bị hủy: dòng tiền). */
    public record Paid(Instant paidAt, long grandTotal) {
    }

    /** Khoản hoàn REFUNDED. */
    public record Refunded(Instant refundedAt, long amount) {
    }

    public static final class StatsQuery implements SalesStatsQueryPort {
        public final List<Paid> paid = new ArrayList<>();
        public final List<Refunded> refunded = new ArrayList<>();
        public long pendingOnline;
        public List<SoldTitle> sold = new ArrayList<>();
        public ReportRange lastTopRange;
        public int lastTopLimit;

        @Override
        public List<DayTotal> grossByDay(UUID tenantId, ReportRange r) {
            Map<LocalDate, long[]> by = new TreeMap<>();
            for (Paid p : paid) {
                if (!p.paidAt().isBefore(r.start()) && p.paidAt().isBefore(r.endExclusive())) {
                    long[] a = by.computeIfAbsent(p.paidAt().atZone(ReportRange.ZONE).toLocalDate(),
                            d -> new long[2]);
                    a[0] += p.grandTotal();
                    a[1]++;
                }
            }
            return by.entrySet().stream().map(e -> new DayTotal(e.getKey(), e.getValue()[0], e.getValue()[1]))
                    .toList();
        }

        @Override
        public List<DayTotal> refundsByDay(UUID tenantId, ReportRange r) {
            Map<LocalDate, long[]> by = new TreeMap<>();
            for (Refunded f : refunded) {
                if (!f.refundedAt().isBefore(r.start()) && f.refundedAt().isBefore(r.endExclusive())) {
                    long[] a = by.computeIfAbsent(f.refundedAt().atZone(ReportRange.ZONE).toLocalDate(),
                            d -> new long[2]);
                    a[0] += f.amount();
                    a[1]++;
                }
            }
            return by.entrySet().stream().map(e -> new DayTotal(e.getKey(), e.getValue()[0], e.getValue()[1]))
                    .toList();
        }

        @Override
        public long countPendingOnlineOrders(UUID tenantId) {
            return pendingOnline;
        }

        @Override
        public List<SoldTitle> topSold(UUID tenantId, ReportRange r, int limit) {
            lastTopRange = r;
            lastTopLimit = limit;
            return sold.stream().sorted(Comparator.comparingLong(SoldTitle::quantity).reversed()
                    .thenComparing(SoldTitle::titleId)).limit(limit).toList();
        }
    }

    /** Danh sách đơn cố định, sắp sẵn theo thứ tự trả về; phân trang đúng offset/limit. */
    public static final class OrderReport implements SalesOrderReportPort {
        public final List<Row> rows = new ArrayList<>();
        public Filter lastFilter;
        public int pages;

        @Override
        public long count(UUID tenantId, Filter filter) {
            lastFilter = filter;
            return rows.size();
        }

        @Override
        public List<Row> page(UUID tenantId, Filter filter, int offset, int limit) {
            pages++;
            int from = Math.min(offset, rows.size());
            return rows.subList(from, Math.min(rows.size(), from + limit));
        }

        public static Row row(int n) {
            return new Row("DEMO-" + n, "POS", "COMPLETED", "PAID", Instant.parse("2026-11-16T10:00:00Z"),
                    Instant.parse("2026-11-16T10:00:00Z"), 2, 90_000, 0, 4_500, 0, 94_500);
        }
    }
}
