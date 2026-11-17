package lms.sales.reporting.application;

import java.util.List;
import java.util.UUID;
import lms.sales.reporting.domain.ReportRange;
import lms.sales.reporting.domain.RevenueTrend.DayTotal;

/**
 * Cổng đọc số liệu bán hàng (chỉ đọc, adapter native có tenant_id tường minh và timeout). Ngày là ngày giờ VN.
 */
public interface SalesStatsQueryPort {

    /** Đơn có paid_at trong khoảng (kể cả đơn sau đó bị hủy), theo ngày thanh toán: amount = tổng grand_total. */
    List<DayTotal> grossByDay(UUID tenantId, ReportRange range);

    /** Khoản hoàn REFUNDED có refunded_at trong khoảng, theo ngày hoàn: amount = tổng amount. */
    List<DayTotal> refundsByDay(UUID tenantId, ReportRange range);

    /** Đơn ONLINE đang PENDING_CONFIRMATION. */
    long countPendingOnlineOrders(UUID tenantId);

    /**
     * Top bán: số lượng và doanh thu của dòng đơn thuộc đơn có paid_at trong khoảng, trừ phần đã có phiếu trả hàng
     * tạo trong khoảng; chỉ trả quantity > 0, quantity giảm dần, đồng hạng titleId tăng.
     */
    List<SoldTitle> topSold(UUID tenantId, ReportRange range, int limit);

    record SoldTitle(UUID titleId, String titleName, long quantity, long revenue) {
    }
}
