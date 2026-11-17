package lms.sales.reporting.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.reporting.domain.ReportRange;

/** Cổng đọc đơn hàng cho báo cáo CSV. Không có thông tin liên hệ khách. Sắp theo created_at giảm dần, id tăng. */
public interface SalesOrderReportPort {

    long count(UUID tenantId, Filter filter);

    List<Row> page(UUID tenantId, Filter filter, int offset, int limit);

    /** channel/status null = không lọc. Lọc theo created_at trong khoảng (ngày giờ VN). */
    record Filter(ReportRange range, String channel, String status) {
    }

    record Row(String orderNo, String channel, String status, String paymentStatus, Instant createdAt,
               Instant paidAt, long itemCount, long subtotal, long discount, long vat, long shippingFee,
               long total) {
    }
}
