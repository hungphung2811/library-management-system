package lms.sales.reporting.domain;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Doanh thu theo DÒNG TIỀN (ADR-0018 mục 2.6): gross = đơn có paid_at trong ngày (kể cả đơn sau đó bị hủy), refunds =
 * khoản hoàn REFUNDED theo ngày hoàn, net = gross - refunds (có thể âm trong một ngày). Điền đủ mọi ngày của khoảng.
 */
public final class RevenueTrend {

    /** Số tiền (VND) và số đơn/khoản của một ngày giờ VN. */
    public record DayTotal(LocalDate date, long amount, long count) {
    }

    public record Point(LocalDate date, long gross, long refunds, long net, long orders) {
    }

    public record Totals(long gross, long refunds, long net, long orders) {
    }

    private final ReportRange range;
    private final List<Point> points;
    private final Totals totals;

    private RevenueTrend(ReportRange range, List<Point> points, Totals totals) {
        this.range = range;
        this.points = points;
        this.totals = totals;
    }

    public static RevenueTrend build(ReportRange range, List<DayTotal> gross, List<DayTotal> refunds) {
        Map<LocalDate, DayTotal> g = new HashMap<>();
        gross.forEach(d -> g.merge(d.date(), d, (a, b) -> new DayTotal(a.date(), a.amount() + b.amount(),
                a.count() + b.count())));
        Map<LocalDate, Long> r = new HashMap<>();
        refunds.forEach(d -> r.merge(d.date(), d.amount(), Long::sum));
        long sumGross = 0;
        long sumRefunds = 0;
        long sumOrders = 0;
        java.util.ArrayList<Point> out = new java.util.ArrayList<>(range.days());
        for (LocalDate day : range.dates()) {
            DayTotal gd = g.get(day);
            long gAmount = gd == null ? 0 : gd.amount();
            long orders = gd == null ? 0 : gd.count();
            long rAmount = r.getOrDefault(day, 0L);
            out.add(new Point(day, gAmount, rAmount, gAmount - rAmount, orders));
            sumGross += gAmount;
            sumRefunds += rAmount;
            sumOrders += orders;
        }
        return new RevenueTrend(range, List.copyOf(out),
                new Totals(sumGross, sumRefunds, sumGross - sumRefunds, sumOrders));
    }

    public ReportRange range() {
        return range;
    }

    public List<Point> points() {
        return points;
    }

    public Totals totals() {
        return totals;
    }
}
