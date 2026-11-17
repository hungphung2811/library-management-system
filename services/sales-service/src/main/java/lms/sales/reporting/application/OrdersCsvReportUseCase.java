package lms.sales.reporting.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lms.common.csv.CsvWriter;
import lms.sales.reporting.application.SalesOrderReportPort.Filter;
import lms.sales.reporting.application.SalesOrderReportPort.Row;
import lms.sales.reporting.domain.ReportRange;
import lms.sales.shared.domain.SalesException;

/**
 * Báo cáo CSV đơn hàng theo created_at (ngày giờ VN). KHÔNG xuất tên/điện thoại/địa chỉ khách (ADR-0018 mục 2.3),
 * không có cột tên đầu sách nên không tra catalog.
 */
public class OrdersCsvReportUseCase {

    static final int BATCH = 500;
    private static final Set<String> CHANNELS = Set.of("POS", "ONLINE");
    private static final Set<String> STATUSES = Set.of("PENDING_CONFIRMATION", "CONFIRMED", "READY_FOR_PICKUP",
            "SHIPPING", "COMPLETED", "CANCELLED");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    public record Prepared(Filter filter, ReportPlan plan) {
    }

    private final SalesOrderReportPort orders;
    private final Clock clock;
    private final int maxRows;

    public OrdersCsvReportUseCase(SalesOrderReportPort orders, Clock clock, int maxRows) {
        this.orders = orders;
        this.clock = clock;
        this.maxRows = maxRows;
    }

    /** Kiểm tham số và đếm; lỗi ném ở đây, trước khi ghi byte đầu. */
    public Prepared prepare(UUID tenantId, LocalDate from, LocalDate to, String channel, String status) {
        LocalDate today = LocalDate.now(clock.withZone(ReportRange.ZONE));
        Filter filter = new Filter(ReportRange.of(from, to, today), normalize(channel, CHANNELS, "channel"),
                normalize(status, STATUSES, "status"));
        long total = orders.count(tenantId, filter);
        return new Prepared(filter, new ReportPlan("orders", "orders-" + DAY.format(today) + ".csv", total,
                (int) Math.min(total, maxRows), total > maxRows));
    }

    /** Ghi tiêu đề và đúng plan.rows() dòng (hoặc ít hơn nếu dữ liệu vừa giảm); trả số dòng đã ghi. Không đóng writer. */
    public int write(UUID tenantId, Prepared prepared, CsvWriter csv) {
        csv.header("orderNo", "channel", "status", "paymentStatus", "createdAt", "paidAt", "itemCount", "subtotal",
                "discount", "vat", "shippingFee", "total");
        int target = prepared.plan().rows();
        int written = 0;
        while (written < target) {
            List<Row> rows = orders.page(tenantId, prepared.filter(), written, Math.min(BATCH, target - written));
            if (rows.isEmpty()) {
                break;
            }
            for (Row r : rows) {
                csv.text(r.orderNo()).text(r.channel()).text(r.status()).text(r.paymentStatus())
                        .text(iso(r.createdAt())).text(iso(r.paidAt())).number(r.itemCount()).number(r.subtotal())
                        .number(r.discount()).number(r.vat()).number(r.shippingFee()).number(r.total()).endRow();
                written++;
            }
        }
        csv.flush();
        return written;
    }

    private static String iso(Instant t) {
        return t == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(t.atZone(ReportRange.ZONE));
    }

    private static String normalize(String value, Set<String> allowed, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) {
            throw SalesException.invalidRequest(name + " không hợp lệ");
        }
        return v;
    }
}
